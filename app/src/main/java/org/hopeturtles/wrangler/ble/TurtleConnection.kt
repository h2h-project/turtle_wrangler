package org.hopeturtles.wrangler.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "TurtleConnection"
private const val PREFERRED_MTU = 185

/** Where the link is. The UI shows these words; keep them plain. */
sealed interface LinkState {
    data object Connecting : LinkState
    data class Preparing(val step: String) : LinkState
    /** [readOnly]: the turtle speaks a newer contract than this app — show
     *  data from the v1 prefix of each value, but send no commands. */
    data class Ready(val readOnly: Boolean) : LinkState
    data class Disconnected(val reason: String?) : LinkState
}

data class DeviceInfo(val manufacturer: String?, val model: String?, val firmware: String?, val serial: String?)

/** Latest decoded value of every characteristic; null = not received yet. */
data class TurtleTelemetry(
    val name: String? = null,
    val contract: ContractInfo? = null,
    val deviceInfo: DeviceInfo? = null,
    val position: Position? = null,
    val nav: Nav? = null,
    val targets: Targets? = null,
    val sail: Sail? = null,
    val power: Power? = null,
    val imu: Imu? = null,
    val status: Status? = null,
    val shore: Shore? = null,
    val environment: Environment? = null,
    val lastUpdateMs: Long = 0,
)

/**
 * One GATT connection to one turtle. Everything goes through [GattQueue]
 * (one outstanding operation at a time). Screens never touch BluetoothGatt —
 * they observe [state] / [telemetry] and send commands via [CommandClient].
 *
 * minSdk 31 is below API 33, where Android switched to byte-array GATT
 * callbacks and write calls, so both variants are implemented; on 33+ the
 * deprecated callbacks are ignored to avoid double delivery.
 */
@SuppressLint("MissingPermission")   // the UI only connects after BLUETOOTH_CONNECT is granted
class TurtleConnection(private val context: Context, val device: BluetoothDevice) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = GattQueue()
    private var gatt: BluetoothGatt? = null
    private val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val _state = MutableStateFlow<LinkState>(LinkState.Connecting)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(TurtleTelemetry())
    val telemetry: StateFlow<TurtleTelemetry> = _telemetry.asStateFlow()

    /** Raw Result (0202) notifications, for [CommandClient]. */
    var onResult: ((ByteArray) -> Unit)? = null

    private var mtu = 23
    val currentMtu: Int get() = mtu

    // True between STATE_CONNECTED and STATE_DISCONNECTED. prepare() checks it
    // after every step so a mid-setup drop ends setup with the real reason
    // instead of carrying on into misleading failures.
    @Volatile private var linkUp = false

    // Android often drops the very first link a few hundred ms in (status 22,
    // "terminated by local host"). A drop during setup gets one quiet retry.
    private var setupRetries = 0

    // One pending deferred per operation type; the queue guarantees only one
    // operation is in flight, so these never overlap.
    private var pendingMtu: CompletableDeferred<Int>? = null
    private var pendingDiscover: CompletableDeferred<Boolean>? = null
    private var pendingRead: CompletableDeferred<ByteArray?>? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingDescWrite: CompletableDeferred<Boolean>? = null

    fun connect() {
        _state.value = LinkState.Connecting
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun retrySetup() {
        setupRetries += 1
        Log.i(TAG, "link dropped during setup — retrying ($setupRetries)")
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        _state.value = LinkState.Preparing("Retrying")
        scope.launch {
            kotlinx.coroutines.delay(600)
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        failPending()
        scope.cancel()
    }

    // ------------------------------------------------------------ operations

    private fun char(service: UUID, uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(service)?.getCharacteristic(uuid)

    suspend fun read(service: UUID, uuid: UUID): ByteArray? {
        val ch = char(service, uuid) ?: return null
        val g = gatt ?: return null
        return queue.run<ByteArray?> { done ->
            pendingRead = done
            g.readCharacteristic(ch)
        }
    }

    /** Write with response. True when the turtle acknowledged the write. */
    suspend fun write(service: UUID, uuid: UUID, value: ByteArray): Boolean {
        val ch = char(service, uuid) ?: return false
        val g = gatt ?: return false
        return queue.run<Boolean> { done ->
            pendingWrite = done
            if (modern) {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = value
                    g.writeCharacteristic(ch)
                }
            }
        } ?: false
    }

    private suspend fun enableNotify(service: UUID, uuid: UUID): Boolean {
        val ch = char(service, uuid) ?: return false
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(TurtleUuids.CCCD) ?: return false
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return queue.run<Boolean> { done ->
            pendingDescWrite = done
            if (modern) {
                g.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = enable; g.writeDescriptor(cccd) }
            }
        } ?: false
    }

    // ------------------------------------------------------------ preparation

    private suspend fun prepare() {
        val g = gatt ?: return
        _state.value = LinkState.Preparing("Negotiating")
        queue.run<Int> { done -> pendingMtu = done; g.requestMtu(PREFERRED_MTU) }
        if (!linkUp) return

        _state.value = LinkState.Preparing("Discovering services")
        var ok = queue.run<Boolean> { done -> pendingDiscover = done; g.discoverServices() } ?: false
        if (!linkUp) return
        if (!ok || g.getService(TurtleUuids.TURTLE_SERVICE) == null) {
            fail("Not a turtle (Turtle service missing)")
            return
        }

        // Android caches a bonded device's GATT table and keeps using it
        // until the device signals Service Changed — so after a firmware
        // update adds a characteristic (e.g. 0118 Environment), a paired
        // phone wouldn't see it. If anything we know is missing, drop the
        // cache (hidden BluetoothGatt.refresh(), via reflection) and
        // discover once more. Harmless when the turtle really lacks it.
        val svc = g.getService(TurtleUuids.TURTLE_SERVICE)
        if (TurtleUuids.TELEMETRY.any { svc.getCharacteristic(it) == null } && refreshGattCache(g)) {
            _state.value = LinkState.Preparing("Refreshing services")
            kotlinx.coroutines.delay(300)
            ok = queue.run<Boolean> { done -> pendingDiscover = done; g.discoverServices() } ?: false
            if (!linkUp) return
            if (!ok || g.getService(TurtleUuids.TURTLE_SERVICE) == null) {
                fail("Not a turtle (Turtle service missing)")
                return
            }
            Log.i(TAG, "GATT cache refreshed; telemetry characteristics now: " +
                TurtleUuids.TELEMETRY.count { g.getService(TurtleUuids.TURTLE_SERVICE).getCharacteristic(it) != null })
        }

        _state.value = LinkState.Preparing("Checking contract")
        val info = read(TurtleUuids.TURTLE_SERVICE, TurtleUuids.CONTRACT_INFO)?.let(TurtleCodec::contractInfo)
        if (!linkUp) return
        if (info == null) {
            fail("Couldn't read the contract version")
            return
        }
        val readOnly = info.contractVersion > TurtleUuids.CONTRACT_VERSION
        val name = read(TurtleUuids.TURTLE_SERVICE, TurtleUuids.TURTLE_NAME)?.let(TurtleCodec::turtleName)
        _telemetry.update { it.copy(contract = info, name = name) }

        _state.value = LinkState.Preparing("Reading turtle")
        readDeviceInfo()
        for (uuid in TurtleUuids.TELEMETRY) {
            read(TurtleUuids.TURTLE_SERVICE, uuid)?.let { apply(uuid, it) }
        }

        if (!linkUp) return
        _state.value = LinkState.Preparing("Subscribing")
        for (uuid in TurtleUuids.TELEMETRY) enableNotify(TurtleUuids.TURTLE_SERVICE, uuid)
        if (!readOnly) enableNotify(TurtleUuids.COMMAND_SERVICE, TurtleUuids.RESULT)

        if (!linkUp) return
        setupRetries = 0
        _state.value = LinkState.Ready(readOnly)
        Log.i(TAG, "ready: ${name} contract v${info.contractVersion} mtu=$mtu readOnly=$readOnly")
    }

    /** Hidden BluetoothGatt.refresh(): clears Android's cached GATT table for
     *  this device. Returns true if the call succeeded. */
    private fun refreshGattCache(g: BluetoothGatt): Boolean = try {
        val m = g.javaClass.getMethod("refresh")
        (m.invoke(g) as? Boolean ?: false).also { Log.i(TAG, "GATT cache refresh -> $it") }
    } catch (e: Exception) {
        Log.w(TAG, "GATT cache refresh unavailable: $e")
        false
    }

    private suspend fun readDeviceInfo() {
        fun s(b: ByteArray?) = b?.toString(Charsets.UTF_8)
        if (gatt?.getService(TurtleUuids.DEVICE_INFO_SERVICE) == null) return
        val di = DeviceInfo(
            manufacturer = s(read(TurtleUuids.DEVICE_INFO_SERVICE, TurtleUuids.MANUFACTURER)),
            model = s(read(TurtleUuids.DEVICE_INFO_SERVICE, TurtleUuids.MODEL)),
            firmware = s(read(TurtleUuids.DEVICE_INFO_SERVICE, TurtleUuids.FIRMWARE)),
            serial = s(read(TurtleUuids.DEVICE_INFO_SERVICE, TurtleUuids.SERIAL)),
        )
        _telemetry.update { it.copy(deviceInfo = di) }
    }

    /** Decode one Turtle-service value into the telemetry snapshot. */
    private fun apply(uuid: UUID, b: ByteArray) {
        val now = System.currentTimeMillis()
        _telemetry.update { t ->
            when (uuid) {
                TurtleUuids.POSITION -> t.copy(position = TurtleCodec.position(b) ?: t.position)
                TurtleUuids.NAV -> t.copy(nav = TurtleCodec.nav(b) ?: t.nav)
                TurtleUuids.TARGETS -> t.copy(targets = TurtleCodec.targets(b) ?: t.targets)
                TurtleUuids.SAIL -> t.copy(sail = TurtleCodec.sail(b) ?: t.sail)
                TurtleUuids.POWER -> t.copy(power = TurtleCodec.power(b) ?: t.power)
                TurtleUuids.IMU -> t.copy(imu = TurtleCodec.imu(b) ?: t.imu)
                TurtleUuids.STATUS -> t.copy(status = TurtleCodec.status(b) ?: t.status)
                TurtleUuids.SHORE -> t.copy(shore = TurtleCodec.shore(b) ?: t.shore)
                TurtleUuids.ENVIRONMENT -> t.copy(environment = TurtleCodec.environment(b) ?: t.environment)
                else -> t
            }.copy(lastUpdateMs = now)
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        _state.value = LinkState.Disconnected(reason)
        gatt?.disconnect()
    }

    private fun failPending() {
        pendingMtu?.complete(mtu)
        pendingDiscover?.complete(false)
        pendingRead?.complete(null)
        pendingWrite?.complete(false)
        pendingDescWrite?.complete(false)
    }

    private fun onChanged(uuid: UUID, value: ByteArray) {
        if (uuid == TurtleUuids.RESULT) onResult?.invoke(value) else apply(uuid, value)
    }

    // ------------------------------------------------------------ callback

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                linkUp = true
                scope.launch { prepare() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                linkUp = false
                failPending()
                val prior = _state.value
                val inSetup = prior is LinkState.Connecting || prior is LinkState.Preparing
                if (inSetup && status != BluetoothGatt.GATT_SUCCESS && setupRetries < 1) {
                    retrySetup()
                    return
                }
                if (prior !is LinkState.Disconnected) {
                    _state.value = LinkState.Disconnected(
                        if (status == BluetoothGatt.GATT_SUCCESS) null else "Link lost (status $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            pendingMtu?.complete(mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingDiscover?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (modern) return
            @Suppress("DEPRECATION")
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ch.value else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingDescWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onChanged(ch.uuid, value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (modern) return
            @Suppress("DEPRECATION")
            onChanged(ch.uuid, ch.value ?: return)
        }
    }
}
