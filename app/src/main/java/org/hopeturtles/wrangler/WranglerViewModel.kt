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
import kotlinx.coroutines.Dispatchers
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
import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.NotStamped
import org.hopeturtles.wrangler.ble.Op
import org.hopeturtles.wrangler.ble.Payloads
import org.hopeturtles.wrangler.ble.ResultCode
import org.hopeturtles.wrangler.ble.TrackPoint
import org.hopeturtles.wrangler.ble.TurtleCodec
import org.hopeturtles.wrangler.ble.TurtleConnection
import org.hopeturtles.wrangler.ble.TurtleScanner
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ble.TurtleUuids
import org.hopeturtles.wrangler.data.JourneyRecord
import org.hopeturtles.wrangler.data.JourneyStore
import org.hopeturtles.wrangler.data.LastSeen
import org.hopeturtles.wrangler.data.LastSeenStore
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.data.StampLogStore
import org.hopeturtles.wrangler.ui.Format
import org.hopeturtles.wrangler.ui.Geo
import org.hopeturtles.wrangler.ui.activeTarget
import org.hopeturtles.wrangler.ui.clockHm
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
    private val journeyStore = JourneyStore(app)

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

    /** The turtle's path this connection (live map trail): a point each time
     *  it has moved [TRAIL_STEP_M] from the last one. Starts empty on connect. */
    private val _trail = MutableStateFlow<List<LatLon>>(emptyList())
    val trail: StateFlow<List<LatLon>> = _trail.asStateFlow()

    /** The connected turtle's journey log, newest first (see [JourneyRecord]). */
    private val _journeys = MutableStateFlow<List<JourneyRecord>>(emptyList())
    val journeys: StateFlow<List<JourneyRecord>> = _journeys.asStateFlow()

    /** False until this turtle's log has loaded; journeys aren't tracked before. */
    private var journeysLoaded = false

    /** Journey files are written one at a time, in order, off the main thread. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val journeyIo = Dispatchers.IO.limitedParallelism(1)

    /** Turtle clock of the open journey's last write, to save its "last seen" now and then. */
    private var journeySavedAt = 0L

    /** Journey sync with the turtle (turtleOS 2.6.0+, see [syncJourneys]): one at a time. */
    private var syncJob: Job? = null
    /** The turtle's newest ended journey already merged (`0119` last_journey_id). */
    private var syncedLastEnded: Long? = null
    /** The open journey whose turtle track was pulled this connection. */
    private var backfilledOpen: Long? = null
    /** After a failed sync (e.g. not paired yet), wait before trying again. */
    private var syncRetryAtMs = 0L

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
        _trail.value = emptyList()
        _journeys.value = emptyList()
        journeysLoaded = false
        syncJob?.cancel()
        syncedLastEnded = null
        backfilledOpen = null
        syncRetryAtMs = 0L
        clockOffsetMs = null
        viewModelScope.launch {
            val log = kotlinx.coroutines.withContext(journeyIo) { journeyStore.load(t.address) }
            if (_connection.value !== link) return@launch
            _journeys.value = log
            journeysLoaded = true
        }
        historyJob = viewModelScope.launch {
            var last: Any? = null
            var lastEnv: Any? = null
            var lastShore: Any? = null
            var lastPos: Any? = null
            link.telemetry.collect { t ->
                val pos = t.position
                if (pos != null && pos !== lastPos) {
                    lastPos = pos
                    if (pos.hasFix && !Format.fixStale(pos)) {
                        val here = LatLon(pos.lat!!, pos.lon!!)
                        val prev = _trail.value.lastOrNull()
                        if (prev == null || (Geo.distanceM(prev, here) ?: 0) >= TRAIL_STEP_M)
                            _trail.value = (_trail.value + here).takeLast(TRAIL_MAX)
                    }
                }
                val sh = t.shore
                if (sh != null && sh !== lastShore) {
                    lastShore = sh
                    sh.deviceNow?.let { clockOffsetMs = it * 1000 - System.currentTimeMillis() }
                    stampStore.markSent(link.device.address, sh.lastShoreSync, t.status?.queueCount)
                        ?.let { _stampLog.value = it }
                }
                trackJourneys(link.device.address, t)
                maybeSyncJourneys(link, t)
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
        syncJob?.cancel()
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

    /**
     * Stale bond: drop this phone's copy of the turtle's pairing, so the next
     * connect pairs afresh. removeBond() is hidden API, reached by reflection;
     * false when Android refuses, and the user forgets the turtle in
     * Android's Bluetooth settings instead.
     */
    fun forgetTurtle(): Boolean {
        val dev = _connection.value?.device ?: return false
        val ok = try {
            dev.javaClass.getMethod("removeBond").invoke(dev) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w("Wrangler", "removeBond unavailable: $e")
            false
        }
        Log.i("Wrangler", "removeBond -> $ok")
        return ok
    }

    /** Start bonding. Android shows its passkey dialog; the code is on the
     *  turtle's OLED, which only accepts pairing on its Bluetooth screen. */
    fun pair() {
        val dev = _connection.value?.device ?: return
        _notice.value = Notice(
            "On the turtle: triple-click to open its Bluetooth screen. " +
                "Enter the 6-digit code it shows.", true)
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
    /** [quiet]: a read the user didn't ask for (e.g. Settings fetching the
     *  SSID) — no notices unless it fails for want of pairing. */
    fun command(label: String, opcode: Int, payload: ByteArray = ByteArray(0),
                quiet: Boolean = false, onResult: (CommandResult) -> Unit = {}) {
        val c = client ?: return
        if (_busy.value != null) return
        _busy.value = label                      // before launch: a double-tap can't send twice
        // Commands that end the link on purpose: don't auto-reconnect after them.
        // Reboot isn't one, since the turtle comes back.
        val off = payload.firstOrNull() == 0.toByte()
        val ends = opcode == Op.SLEEP || (opcode == Op.BLE_SET_ENABLED && off) ||
            (opcode == Op.SET_TURTLE_MODE && off)
        val link = _connection.value
        if (ends) link?.expectEnd()
        viewModelScope.launch {
            if (!quiet) _notice.value = Notice("$label…", true)
            val r = try { c.send(opcode, payload) } finally { _busy.value = null }
            if (ends && !r.code.ok && link?.state?.value is LinkState.Ready) link.expectEnd(false)
            if (!quiet || r.code == ResultCode.NOT_BONDED) _notice.value = Notice("$label: ${r.message}", r.code.ok)
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

    // ------------------------------------------------------------ journeys

    /**
     * JOURNEY_START. The turtle stamps its own GPS fix as the departure; the
     * contract doesn't echo it, so the phone records the turtle's position
     * from the same moment for the map.
     */
    fun startJourney() {
        val link = _connection.value ?: return
        command("Start journey", Op.JOURNEY_START) { r ->
            when {
                r.code.ok -> {
                    val id = Payloads.journeyId(r.payload)
                    val tel = link.telemetry.value
                    val p = tel.position?.takeIf { it.hasFix }
                    if (id != null && p != null && journeysLoaded) {
                        val r0 = _journeys.value.find { it.id == id } ?: JourneyRecord(id)
                        putJourney(link.device.address, r0.copy(departure = LatLon(p.lat!!, p.lon!!),
                            destination = activeTarget(tel.targets) ?: r0.destination))
                    }
                    say("Journey started" + (id?.let { " at " + clockHm(it) } ?: "") +
                        ". Every reading is tagged until you end it.", true)
                }
                r.code == ResultCode.WRONG_STATE -> say("A journey is already open on the turtle.", false)
                else -> say("Couldn't start the journey: ${r.message}", false)
            }
        }
    }

    /**
     * JOURNEY_END. The turtle stamps its arrival from its GPS (if it has a
     * fix); the phone logs the turtle's position from the same moment.
     */
    fun endJourney() {
        val link = _connection.value ?: return
        val jid = link.telemetry.value.shore?.journeyId
        command("End journey", Op.JOURNEY_END) { r ->
            val stamped = if (r.code.ok) Payloads.arrivalStamped(r.payload) else null
            if (r.code.ok && jid != null && journeysLoaded) {
                _journeys.value.find { it.id == jid && !it.ended }?.let { j ->
                    val p = link.telemetry.value.position?.takeIf { it.hasFix && stamped == true }
                    putJourney(link.device.address, j.copy(endedAt = turtleNow(), endSeen = true,
                        lastSeenAt = turtleNow(), arrival = p?.let { LatLon(it.lat!!, it.lon!!) }))
                }
            }
            when {
                r.code.ok && stamped == false -> say("Journey ended — arrival point not recorded (no GPS).", true)
                r.code.ok -> say("Journey ended. Arrival point recorded.", true)
                r.code == ResultCode.WRONG_STATE -> say("No journey is open on the turtle.", false)
                else -> say("Couldn't end the journey: ${r.message}", false)
            }
        }
    }

    /** The turtle's clock now (unix s), from the last `device_now`; the phone's without one. */
    private fun turtleNow(): Long = (System.currentTimeMillis() + (clockOffsetMs ?: 0)) / 1000

    /**
     * Keep the journey log in step with the turtle (Status bit 3 + Shore
     * `journey_id`): log the open journey's route and destination, and
     * close any journey the turtle has ended. An end is "seen" when this
     * phone had the journey in view within [END_SEEN_S]; otherwise it ended
     * while the phone was away, some time after it was last seen open.
     */
    private fun trackJourneys(address: String, t: TurtleTelemetry) {
        if (!journeysLoaded) return
        val st = t.status ?: return
        val open = if (st.journeyActive) t.shore?.journeyId ?: return else null
        val now = turtleNow()
        val here = t.position?.takeIf { it.hasFix && !Format.fixStale(it) }?.let { LatLon(it.lat!!, it.lon!!) }
        _journeys.value.filter { !it.ended && it.id != open }.forEach { j ->
            val seen = now - j.lastSeenAt <= END_SEEN_S
            putJourney(address, j.copy(endedAt = now, endSeen = seen, arrival = if (seen) here else null))
        }
        if (open == null) return
        val old = _journeys.value.find { it.id == open }
        if (old?.ended == true) return
        val base = old ?: JourneyRecord(open)
        var j = base.copy(lastSeenAt = now, destination = activeTarget(t.targets) ?: base.destination)
        // turtleOS 2.6.0: the departure the turtle stamped, even for a journey started on its screen.
        t.journey?.takeIf { it.openId == open }?.departure?.let { j = j.copy(departure = it, departureFromTurtle = true) }
        here?.let { p -> j.withPoint(p, now, TRAIL_STEP_M, TRACK_MAX)?.let { j = it } }
        val changed = old == null || j.track !== old.track || j.destination != old.destination ||
            j.departure != old.departure
        if (changed || now - journeySavedAt >= SEEN_SAVE_S) putJourney(address, j)
        else _journeys.value = _journeys.value.map { if (it.id == open) j else it }
    }

    /**
     * Pull the turtle's own journey records (turtleOS 2.6.0, `0119` present)
     * when there's something new: a newly ended journey (`last_journey_id`
     * changed), or an open journey whose earlier track this phone hasn't
     * seen. Quiet: no notice, no busy state, nothing asked of the user; a
     * failure (not paired yet, link gone) just waits [SYNC_RETRY_MS].
     */
    private fun maybeSyncJourneys(link: TurtleConnection, t: TurtleTelemetry) {
        if (!journeysLoaded || syncJob?.isActive == true) return
        val ji = t.journey ?: return
        val st = link.state.value
        if (st !is LinkState.Ready || st.readOnly) return
        if (System.currentTimeMillis() < syncRetryAtMs) return
        val list = ji.lastEndedId != null && ji.lastEndedId != syncedLastEnded
        val open = ji.openId?.takeIf { it != backfilledOpen }
        if (!list && open == null) return
        syncJob = viewModelScope.launch {
            val ok = syncJourneys(link, ji.lastEndedId.takeIf { list }, open)
            if (!ok) syncRetryAtMs = System.currentTimeMillis() + SYNC_RETRY_MS
        }
    }

    /**
     * The sync itself. Ended journeys come newest first from JOURNEY_LIST;
     * each new one is merged ([JourneyRecord.withTurtle]) with its track
     * ([JourneyRecord.withTurtlePoints]), stopping at the first one already
     * merged with its track. Then the open journey's track so far. Returns
     * false if the turtle stopped answering.
     */
    private suspend fun syncJourneys(link: TurtleConnection, lastEnded: Long?, open: Long?): Boolean {
        val c = client ?: return false
        val address = link.device.address
        if (lastEnded != null) {
            var i = 0
            var count = 1
            while (i < count) {
                val r = c.send(Op.JOURNEY_LIST, Payloads.journeyList(i))
                if (r.code == ResultCode.BAD_VALUE) break                    // past the end
                if (!r.code.ok) return false
                val (n, tj) = TurtleCodec.journeyListResult(r.payload) ?: return false
                count = n
                val have = _journeys.value.find { it.id == tj.id }
                if (have != null && have.turtle && have.turtleTrack) break    // older ones are done
                val pts = fetchTrack(c, tj.id)
                // Re-read: the live tracker may have touched it while we waited.
                var rec = (_journeys.value.find { it.id == tj.id } ?: JourneyRecord(tj.id)).withTurtle(tj)
                if (pts != null) rec = rec.withTurtlePoints(pts, TRACK_MAX).copy(turtleTrack = true)
                if (_connection.value !== link) return false
                putJourney(address, rec)
                i++
            }
            syncedLastEnded = lastEnded
        }
        if (open != null) {
            val pts = fetchTrack(c, open) ?: return false
            if (_connection.value !== link) return false
            val cur = _journeys.value.find { it.id == open } ?: JourneyRecord(open)
            putJourney(address, cur.withTurtlePoints(pts, TRACK_MAX))
            backfilledOpen = open
        }
        return true
    }

    /** Every JOURNEY_TRACK page of journey [id], or null if the turtle refused. */
    private suspend fun fetchTrack(c: CommandClient, id: Long): List<TrackPoint>? {
        val out = ArrayList<TrackPoint>()
        var start = 0
        while (true) {
            val r = c.send(Op.JOURNEY_TRACK, Payloads.journeyTrack(id, start))
            if (!r.code.ok) return null
            val page = TurtleCodec.journeyTrackResult(r.payload) ?: return null
            out += page.points
            start += page.n
            if (page.n == 0 || start >= page.total) return out
        }
    }

    /** Add or replace [j] in the log (newest first, at most [JourneyStore.MAX]) and write it. */
    private fun putJourney(address: String, j: JourneyRecord) {
        val all = (_journeys.value.filter { it.id != j.id } + j).sortedByDescending { it.id }
            .take(JourneyStore.MAX)
        _journeys.value = all
        journeySavedAt = turtleNow()
        val keep = all.map { it.id }
        viewModelScope.launch(journeyIo) {
            journeyStore.save(address, j)
            journeyStore.prune(address, keep)
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
        const val TRAIL_STEP_M = 5L            // GPS jitter at rest stays under this
        const val TRAIL_MAX = 5_000
        const val TRACK_MAX = 4_000            // per journey; thinned, not cut, when full
        const val END_SEEN_S = 120L            // ended within this of being seen open = seen
        const val SEEN_SAVE_S = 60L
        const val SYNC_RETRY_MS = 60_000L
    }
}
