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
import kotlinx.coroutines.launch
import org.hopeturtles.wrangler.ble.CommandClient
import org.hopeturtles.wrangler.ble.CommandResult
import org.hopeturtles.wrangler.ble.FoundTurtle
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.Op
import org.hopeturtles.wrangler.ble.ResultCode
import org.hopeturtles.wrangler.ble.TurtleConnection
import org.hopeturtles.wrangler.ble.TurtleScanner
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.data.LastSeen
import org.hopeturtles.wrangler.data.LastSeenStore
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One message line under the controls: what the last action did. */
data class Notice(val text: String, val ok: Boolean)

@SuppressLint("MissingPermission")   // MainActivity gates everything on BLUETOOTH_SCAN/CONNECT
class WranglerViewModel(app: Application) : AndroidViewModel(app) {

    val scanner = TurtleScanner(app)
    private val lastSeenStore = LastSeenStore(app)

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

    /** Battery voltage (mV) samples for this connection — the Dashboard
     *  sparkline. Voltage, not SoC: SoC moves in coarse 1 % steps. */
    private val _battHistory = MutableStateFlow<List<Int>>(emptyList())
    val battHistory: StateFlow<List<Int>> = _battHistory.asStateFlow()

    // ------------------------------------------------------------ scanning

    fun startScan() {
        _noTurtles.value = false
        scanner.start()
        scanWatch?.cancel()
        scanWatch = viewModelScope.launch {
            delay(NO_TURTLE_AFTER_MS)
            if (scanner.found.value.isEmpty()) {
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
        historyJob = viewModelScope.launch {
            var last: Any? = null
            link.telemetry.collect { t ->
                val p = t.power
                if (p != null && p !== last) {
                    last = p
                    p.voltageMv?.let { mv -> _battHistory.value = (_battHistory.value + mv).takeLast(HISTORY_MAX) }
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

    /** Run a command and turn its result into the notice line. */
    fun command(label: String, opcode: Int, payload: ByteArray = ByteArray(0),
                onResult: (CommandResult) -> Unit = {}) {
        val c = client ?: return
        viewModelScope.launch {
            _notice.value = Notice("$label…", true)
            val r = c.send(opcode, payload)
            _notice.value = Notice("$label: ${r.message}", r.code.ok)
            if (r.code == ResultCode.NOT_BONDED) pair()
            onResult(r)
        }
    }

    /**
     * Phase 1 round-trip check: re-send the turtle's current telemetry
     * interval. Harmless (nothing changes) but exercises the whole command
     * path, including bonding.
     */
    fun testCommand(tel: TurtleTelemetry) {
        val secs = tel.status?.intervalS ?: 120
        val p = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(secs.toShort()).array()
        command("Test command", Op.TELEMETRY_SET_INTERVAL, p)
    }

    override fun onCleared() {
        try { getApplication<Application>().unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        disconnect()
        stopScan()
    }

    companion object {
        const val NO_TURTLE_AFTER_MS = 12_000L
        const val HISTORY_MAX = 120
    }
}
