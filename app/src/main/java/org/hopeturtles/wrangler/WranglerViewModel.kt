package org.hopeturtles.wrangler

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.hopeturtles.wrangler.ble.CommandClient
import org.hopeturtles.wrangler.ble.CommandResult
import org.hopeturtles.wrangler.ble.FoundTurtle
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.NotStamped
import org.hopeturtles.wrangler.ble.Op
import org.hopeturtles.wrangler.ble.Payloads
import org.hopeturtles.wrangler.ble.ResultCode
import org.hopeturtles.wrangler.ble.TurtleConnection
import org.hopeturtles.wrangler.ble.TurtleScanner
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ble.TurtleUuids
import org.hopeturtles.wrangler.data.LastSeen
import org.hopeturtles.wrangler.data.LastSeenStore
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.data.StampLogStore
import org.hopeturtles.wrangler.ui.tzLabel
import java.util.TimeZone

/** One battery reading; [ma] is the raw INA219 sign — negative = charging (contract v1). */
data class BattSample(val tMs: Long, val mv: Int?, val ma: Int?, val soc: Int?)

/** One control-bottle reading (Environment 0118). */
data class EnvSample(
    val tMs: Long, val airTempC: Double?, val humidityPct: Double?,
    val baroTempC: Double?, val boardTempC: Double?, val pressureHpa: Double?,
)

/** One message line under the controls: what the last action did. */
data class Notice(val text: String, val ok: Boolean)

@SuppressLint("MissingPermission")   // MainActivity gates everything on BLUETOOTH_SCAN/CONNECT
class WranglerViewModel(app: Application) : AndroidViewModel(app) {

    val scanner = TurtleScanner(app)
    private val lastSeenStore = LastSeenStore(app)
    private val stampStore = StampLogStore(app)

    private val _connection = MutableStateFlow<TurtleConnection?>(null)
    val connection: StateFlow<TurtleConnection?> = _connection.asStateFlow()
    private var client: CommandClient? = null

    /** True after a scan has run [NO_TURTLE_AFTER_MS] and found nothing. */
    private val _noTurtles = MutableStateFlow(false)
    val noTurtles: StateFlow<Boolean> = _noTurtles.asStateFlow()

    private val _lastSeen = MutableStateFlow(lastSeenStore.all())
    val lastSeen: StateFlow<List<LastSeen>> = _lastSeen.asStateFlow()

    private val _bondState = MutableStateFlow(BluetoothDevice.BOND_NONE)
    val bondState: StateFlow<Int> = _bondState.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private var scanWatch: Job? = null
    private var snapshotJob: Job? = null
    private var historyJob: Job? = null

    /** Battery samples for this connection: the Dashboard sparkline and the
     *  full-screen charging graph. One per Power notification (~5 s). */
    private val _battHistory = MutableStateFlow<List<BattSample>>(emptyList())
    val battHistory: StateFlow<List<BattSample>> = _battHistory.asStateFlow()

    /** Control-bottle readings for this connection (Bottle tile + graph). */
    private val _envHistory = MutableStateFlow<List<EnvSample>>(emptyList())
    val envHistory: StateFlow<List<EnvSample>> = _envHistory.asStateFlow()

    /** The connected turtle's manual-stamp log (GPS tab), oldest first. */
    private val _stampLog = MutableStateFlow<List<StampEntry>>(emptyList())
    val stampLog: StateFlow<List<StampEntry>> = _stampLog.asStateFlow()

    /** Turtle clock minus phone clock (ms), from the last `device_now`. */
    private var clockOffsetMs: Long? = null

    // ------------------------------------------------------------ scanning

    fun startScan() {
        _noTurtles.value = false
        scanner.start()
        scanWatch?.cancel()
        // Stop as soon as a turtle is listed; flag "no turtles" if none
        // turns up within NO_TURTLE_AFTER_MS (the scan keeps going then).
        scanWatch = viewModelScope.launch {
            val hit = withTimeoutOrNull(NO_TURTLE_AFTER_MS) { scanner.found.first { it.isNotEmpty() } }
            if (hit != null) {
                scanner.stop()
            } else {
                _noTurtles.value = true
                _lastSeen.value = lastSeenStore.all()
            }
        }
    }

    fun stopScan() {
        scanWatch?.cancel()
        scanner.stop()
    }

    // ------------------------------------------------------------ connecting

    fun connect(t: FoundTurtle) {
        stopScan()
        disconnect()
        val link = TurtleConnection(getApplication(), t.device)
        client = CommandClient(link)
        _connection.value = link
        _bondState.value = t.device.bondState
        _notice.value = null
        link.connect()
        _battHistory.value = emptyList()
        _envHistory.value = emptyList()
        _stampLog.value = stampStore.load(t.address)
        clockOffsetMs = null
        historyJob = viewModelScope.launch {
            var last: Any? = null
            var lastEnv: Any? = null
            var lastShore: Any? = null
            link.telemetry.collect { t ->
                val sh = t.shore
                if (sh != null && sh !== lastShore) {
                    lastShore = sh
                    sh.deviceNow?.let { clockOffsetMs = it * 1000 - System.currentTimeMillis() }
                    stampStore.markSent(link.device.address, sh.lastShoreSync, t.status?.queueCount)
                        ?.let { _stampLog.value = it }
                }
                val e = t.environment
                if (e != null && e !== lastEnv) {
                    lastEnv = e
                    val sample = EnvSample(System.currentTimeMillis(), e.airTempC, e.humidityPct,
                        e.baroTempC, e.boardTempC, e.pressureHpa)
                    _envHistory.value = (_envHistory.value + sample).takeLast(HISTORY_MAX)
                }
                val p = t.power
                if (p != null && p !== last) {
                    last = p
                    if (p.voltageMv != null || p.currentMa != null) {
                        val sample = BattSample(System.currentTimeMillis(), p.voltageMv, p.currentMa, p.socPct)
                        _battHistory.value = (_battHistory.value + sample).takeLast(HISTORY_MAX)
                    }
                }
            }
        }
        snapshotJob = viewModelScope.launch {
            // Keep the "last seen" cache fresh while connected.
            while (true) {
                delay(5_000)
                val tel = link.telemetry.value
                if (link.state.value is LinkState.Ready && tel.lastUpdateMs > 0) {
                    lastSeenStore.save(t.address, tel)
                }
            }
        }
    }

    fun disconnect() {
        snapshotJob?.cancel()
        historyJob?.cancel()
        servoJob?.cancel()
        servoWanted = null
        _connection.value?.let { link ->
            val tel = link.telemetry.value
            if (tel.lastUpdateMs > 0) lastSeenStore.save(link.device.address, tel)
            link.disconnect()
            link.close()
        }
        _connection.value = null
        client = null
        _lastSeen.value = lastSeenStore.all()
    }

    // ------------------------------------------------------------ pairing

    /** Start bonding. Android shows its passkey dialog; the code is on the
     *  turtle's OLED, which only accepts pairing on its Bluetooth screen. */
    fun pair() {
        val dev = _connection.value?.device ?: return
        _notice.value = Notice(
            "On the turtle: triple-click, then single-click three times to open its " +
                "Bluetooth screen. Enter the 6-digit code it shows.", true)
        val started = dev.createBond()
        Log.i("Wrangler", "createBond -> $started (bondState ${dev.bondState})")
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = _connection.value?.device ?: return
            @Suppress("DEPRECATION")
            val d: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (d?.address != dev.address) return
            val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            _bondState.value = state
            Log.i("Wrangler", "bond state -> $state")
            if (state == BluetoothDevice.BOND_BONDED) _notice.value = Notice("Paired.", true)
        }
    }

    init {
        ContextCompat.registerReceiver(
            app, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    // ------------------------------------------------------------ commands

    /** Label of the command in flight, or null. Screens disable their buttons on it. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /**
     * Run a command and turn its result into the notice line. [onResult]
     * runs after the generic notice is set, so it can replace it with
     * something more specific via [say].
     */
    fun command(label: String, opcode: Int, payload: ByteArray = ByteArray(0),
                onResult: (CommandResult) -> Unit = {}) {
        val c = client ?: return
        if (_busy.value != null) return
        _busy.value = label                      // before launch: a double-tap can't send twice
        viewModelScope.launch {
            _notice.value = Notice("$label…", true)
            val r = try { c.send(opcode, payload) } finally { _busy.value = null }
            _notice.value = Notice("$label: ${r.message}", r.code.ok)
            if (r.code == ResultCode.NOT_BONDED) pair()
            onResult(r)
        }
    }

    // ------------------------------------------------------------ servo dial

    /** Latest angle the dial asked for and not yet sent (main thread only). */
    private var servoWanted: Int? = null
    private var servoJob: Job? = null

    /**
     * Servo dial: move the sail servo to [deg] (0–180 of its full travel).
     * Latest wins — while one SERVO_SET_ANGLE is in flight, newer angles
     * replace each other, so a fast drag never queues up a backlog and the
     * turtle always ends where the finger stopped. Successes are silent; a
     * failure stops the stream and says why.
     */
    fun servoTo(deg: Int) {
        val c = client ?: return
        servoWanted = deg
        if (servoJob?.isActive == true) return
        servoJob = viewModelScope.launch {
            while (true) {
                val d = servoWanted ?: break
                servoWanted = null
                val r = c.send(Op.SERVO_SET_ANGLE, Payloads.servoAngle(d))
                if (!r.code.ok) {
                    servoWanted = null
                    _notice.value = Notice("Sail servo: ${r.message}", false)
                    if (r.code == ResultCode.NOT_BONDED) pair()
                    break
                }
            }
        }
    }

    // ------------------------------------------------------------ GPS stamps

    /**
     * GPS tab's Stamp button: one GPS_STAMP, logged on the phone whatever
     * the outcome, with the turtle's position from the latest telemetry.
     */
    fun stamp() {
        val link = _connection.value ?: return
        command("Stamp", Op.GPS_STAMP) { r ->
            val tel = link.telemetry.value
            val now = System.currentTimeMillis()
            val ok = if (r.code.ok) Payloads.stampOk(r.payload) else null
            val reason = when {
                ok != null -> null
                r.code.ok -> "the turtle's answer was unreadable"
                r.code == ResultCode.NOT_STAMPED -> Payloads.notStampedReason(r.payload).why
                r.code == ResultCode.WRONG_STATE -> "the turtle isn't in manual logging mode"
                else -> r.message.trimEnd('.').replaceFirstChar { it.lowercase() }
            }
            val p = tel.position?.takeIf { it.hasFix && ok?.hasPosition == true }
            val entry = StampEntry(
                atMs = now,
                turtleAt = clockOffsetMs?.let { (now + it) / 1000 },
                journeyId = tel.shore?.journeyId,
                ok = ok != null,
                hasPosition = ok?.hasPosition == true,
                stampsSession = ok?.stampsSession,
                reason = reason,
                lat = p?.lat, lon = p?.lon, sats = p?.sats,
            )
            _stampLog.value = stampStore.add(link.device.address, entry)
            say(
                when {
                    ok == null && r.code == ResultCode.NOT_STAMPED &&
                        Payloads.notStampedReason(r.payload) == NotStamped.CLOCK_NOT_SET ->
                        "Not stamped — $reason. Set the turtle clock from this phone below."
                    ok == null -> "Not stamped — $reason."
                    !ok.hasPosition -> "Stamped — no position (sensor values and time only). #${ok.stampsSession} this boot."
                    else -> "Stamped. #${ok.stampsSession} this boot."
                },
                ok != null,
            )
        }
    }

    // ------------------------------------------------------------ turtle clock

    /**
     * Line the turtle up with this phone: TIME_SET (UTC), then
     * TIMEZONE_SET with the phone's current UTC offset (contract: "Set
     * turtle clock to phone time" means both). Re-reads Shore and Status
     * afterwards so Diagnostics shows the new time and zone straight away.
     */
    fun setTurtleClock() {
        val link = _connection.value ?: return
        val nowMs = System.currentTimeMillis()
        val tzMin = TimeZone.getDefault().getOffset(nowMs) / 60_000
        command("Set turtle clock", Op.TIME_SET, Payloads.unixTime(nowMs / 1000)) { r ->
            if (!r.code.ok) {
                say(when (r.code) {
                    ResultCode.BAD_VALUE ->
                        "The turtle refused this phone's time (it must be 2020–2099). Check the phone's clock."
                    else -> "Couldn't set the turtle clock: ${r.message}"
                }, false)
                return@command
            }
            val chipLost = if (Payloads.rtcChipWritten(r.payload) == false)
                " Its clock chip didn't take it, so the time will be lost when the turtle restarts." else ""
            command("Set turtle time zone", Op.TIMEZONE_SET, Payloads.tzOffset(tzMin)) { z ->
                when {
                    z.code.ok -> say("Turtle clock and time zone (${tzLabel(tzMin)}) set to this phone's.$chipLost", true)
                    z.code == ResultCode.UNKNOWN_OPCODE -> say("Turtle clock set to this phone's time, but this " +
                        "turtle's firmware can't take a time zone. Update turtleOS.$chipLost", true)
                    else -> say("Turtle clock set, but not its time zone: ${z.message}$chipLost", false)
                }
                viewModelScope.launch {
                    link.reread(TurtleUuids.SHORE)
                    link.reread(TurtleUuids.STATUS)
                }
            }
        }
    }

    /**
     * SECURE_MODE_SET. The turtle refuses to turn it on while its RTC
     * battery is faulty, since with secure mode the clock chip is its only
     * time source at sea.
     */
    fun setSecureMode(on: Boolean) {
        val link = _connection.value ?: return
        command(if (on) "Turn secure mode on" else "Turn secure mode off",
            Op.SECURE_MODE_SET, byteArrayOf(if (on) 1 else 0)) { r ->
            when {
                r.code.ok -> say(if (on) "Secure mode is on: the turtle no longer trusts GPS time."
                    else "Secure mode is off.", true)
                r.code == ResultCode.WRONG_STATE && Payloads.secureRefusedForBattery(r.payload) -> say(
                    "Secure mode can't turn on: the turtle's clock-chip battery is faulty. " +
                        "Replace the coin cell first.", false)
                else -> say("Couldn't change secure mode: ${r.message}", false)
            }
            if (r.code.ok) viewModelScope.launch { link.reread(TurtleUuids.STATUS) }
        }
    }

    fun say(text: String, ok: Boolean) {
        _notice.value = Notice(text, ok)
    }

    override fun onCleared() {
        try { getApplication<Application>().unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        disconnect()
        stopScan()
    }

    companion object {
        const val NO_TURTLE_AFTER_MS = 12_000L
        const val HISTORY_MAX = 2_880          // 4 h at the Power characteristic's 5 s rate
    }
}
