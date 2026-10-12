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
    /** Linked, and working through the setup handshake; [Handshake] says where. */
    data object Preparing : LinkState
    /** [readOnly]: the turtle speaks a newer contract than this app — show
     *  data from the v1 prefix of each value, but send no commands. */
    data class Ready(val readOnly: Boolean) : LinkState
    data class Disconnected(val reason: String?) : LinkState
}

/** What a GATT read callback delivered; [LINK_DROPPED] when the link went first. */
private class ReadReply(val status: Int, val value: ByteArray?)
private const val LINK_DROPPED = -1

/** How one read went, in words precise enough to debug from the drawer. */
private sealed interface Read {
    class Ok(val value: ByteArray) : Read
    class Missing(val what: String) : Read
    data object Refused : Read
    data object Timeout : Read
    data object NoLink : Read
    class Status(val code: Int) : Read

    fun describe(): String = when (this) {
        is Ok -> "${value.size} byte(s) [${hex(value)}]"
        is Missing -> what
        Refused -> "Android refused to start the read (readCharacteristic returned false)"
        Timeout -> "no reply within 5 s"
        NoLink -> "the link dropped before the reply"
        is Status -> gattStatusName(code)
    }
}

private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

/** "0101" for a contract UUID, the full UUID otherwise. */
private fun shortId(u: UUID): String {
    val s = u.toString()
    return if (s.endsWith("-5890-45f2-b0be-090d35436a95") && s.startsWith("26d0")) s.substring(4, 8) else s
}

/** GATT status with its name, e.g. "GATT status 5 (insufficient authentication)". */
internal fun gattStatusName(code: Int): String {
    val name = when (code) {
        2 -> "read not permitted"
        3 -> "write not permitted"
        5 -> "insufficient authentication"
        6 -> "request not supported"
        7 -> "invalid offset"
        8 -> "insufficient authorization, or connection timeout"
        13 -> "invalid attribute length"
        15 -> "insufficient encryption"
        19 -> "remote device ended the connection"
        22 -> "terminated by local host"
        34 -> "LMP response timeout"
        62 -> "failed to establish connection"
        128 -> "no resources"
        129 -> "internal error"
        133 -> "GATT_ERROR, generic Android failure"
        137 -> "authentication failed"
        143 -> "connection congested"
        257 -> "failure"
        else -> null
    }
    return "GATT status $code" + (name?.let { " ($it)" } ?: "")
}

/** The setup handshake, in order. The connecting drawer lists all of them. */
enum class HandshakeStep(val label: String) {
    LINK("Bluetooth link"),
    MTU("Packet size"),
    SERVICES("Services"),
    CONTRACT("Contract"),
    READ("Reading the turtle"),
    SUBSCRIBE("Live updates"),
}

/**
 * Setup progress for the connecting drawer. Steps before [step] are done
 * and [found] says what each one learned; [now] is what [step] is doing.
 * [failed] is why setup stopped, set only when it stops before Ready.
 */
data class Handshake(
    val step: HandshakeStep = HandshakeStep.LINK,
    val now: String? = null,
    val found: Map<HandshakeStep, String> = emptyMap(),
    val failed: String? = null,
)

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

    private val _handshake = MutableStateFlow(Handshake())
    val handshake: StateFlow<Handshake> = _handshake.asStateFlow()

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
    private var pendingRead: CompletableDeferred<ReadReply>? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingDescWrite: CompletableDeferred<Boolean>? = null

    fun connect() {
        _state.value = LinkState.Connecting
        _handshake.value = Handshake(now = "Connecting to ${device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun retrySetup(status: Int) {
        setupRetries += 1
        Log.i(TAG, "link dropped during setup — retrying ($setupRetries)")
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        _state.value = LinkState.Connecting
        // Keep the first failure on screen: the step it hit and the status.
        val during = _handshake.value.step.label
        _handshake.value = Handshake(
            now = "Link dropped during \"$during\" (${gattStatusName(status)}); trying again")
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

    suspend fun read(service: UUID, uuid: UUID): ByteArray? = (readDetailed(service, uuid) as? Read.Ok)?.value

    /** A read that says exactly how it went, for the setup drawer. */
    private suspend fun readDetailed(service: UUID, uuid: UUID): Read {
        val g = gatt ?: return Read.NoLink
        val svc = g.getService(service) ?: return Read.Missing("service ${shortId(service)} not in the GATT table")
        val ch = svc.getCharacteristic(uuid) ?: return Read.Missing(
            "${shortId(uuid)} not in the GATT table; service ${shortId(service)} has " +
                svc.characteristics.joinToString(" ") { shortId(it.uuid) }.ifEmpty { "nothing" })
        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0)
            return Read.Missing("${shortId(uuid)} is not readable (properties 0x%02x)".format(ch.properties))
        var started = false
        val reply = queue.run<ReadReply> { done ->
            pendingRead = done
            g.readCharacteristic(ch).also { started = it }
        }
        return when {
            !started -> Read.Refused
            reply == null -> Read.Timeout
            reply.status == LINK_DROPPED -> Read.NoLink
            reply.status != BluetoothGatt.GATT_SUCCESS -> Read.Status(reply.status)
            else -> Read.Ok(reply.value ?: ByteArray(0))
        }
    }

    /** Read one Turtle-service characteristic now instead of waiting for its
     *  next notification (e.g. Shore after TIME_SET; it notifies once a minute). */
    suspend fun reread(uuid: UUID) {
        read(TurtleUuids.TURTLE_SERVICE, uuid)?.let { apply(uuid, it) }
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

    private fun begin(step: HandshakeStep, now: String) {
        _state.value = LinkState.Preparing
        _handshake.update { it.copy(step = step, now = now) }
    }

    private fun found(step: HandshakeStep, what: String) {
        _handshake.update { it.copy(found = it.found + (step to what)) }
    }

    private suspend fun prepare() {
        val g = gatt ?: return
        found(HandshakeStep.LINK, if (setupRetries > 0) "Connected on the second try" else "Connected")
        begin(HandshakeStep.MTU, "Asking for $PREFERRED_MTU-byte packets")
        queue.run<Int> { done -> pendingMtu = done; g.requestMtu(PREFERRED_MTU) }
        if (!linkUp) return
        found(HandshakeStep.MTU, "MTU $mtu, up to ${mtu - 3} bytes a value")

        begin(HandshakeStep.SERVICES, "Discovering services")
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
        var refreshed = false
        if (TurtleUuids.TELEMETRY.any { svc.getCharacteristic(it) == null } && refreshGattCache(g)) {
            refreshed = true
            _handshake.update { it.copy(now = "Some are missing; refreshing Android's cached list") }
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

        val have = TurtleUuids.TELEMETRY.count { g.getService(TurtleUuids.TURTLE_SERVICE).getCharacteristic(it) != null }
        found(HandshakeStep.SERVICES, "${g.services.size} services, $have of ${TurtleUuids.TELEMETRY.size} telemetry values" +
            if (refreshed) " (cache refreshed)" else "")

        begin(HandshakeStep.CONTRACT, "Reading Contract Info (${shortId(TurtleUuids.CONTRACT_INFO)})")
        val contractRead = readDetailed(TurtleUuids.TURTLE_SERVICE, TurtleUuids.CONTRACT_INFO)
        Log.i(TAG, "contract read: ${contractRead.describe()}")
        if (!linkUp) return
        val raw = (contractRead as? Read.Ok)?.value
        if (raw == null) {
            fail("Couldn't read Contract Info: ${contractRead.describe()}")
            return
        }
        val info = TurtleCodec.contractInfo(raw)
        if (info == null) {
            fail("Contract Info too short: got ${raw.size} byte(s) [${hex(raw)}], need at least 2")
            return
        }
        val readOnly = info.contractVersion > TurtleUuids.CONTRACT_VERSION
        val name = read(TurtleUuids.TURTLE_SERVICE, TurtleUuids.TURTLE_NAME)?.let(TurtleCodec::turtleName)
        _telemetry.update { it.copy(contract = info, name = name) }
        found(HandshakeStep.CONTRACT, "v${info.contractVersion}, mode ${info.turtleMode} [${hex(raw)}]" + (name?.let { ", $it" } ?: "") +
            if (readOnly) " (newer than this app's v${TurtleUuids.CONTRACT_VERSION}: read-only)" else "")

        begin(HandshakeStep.READ, "Device information")
        readDeviceInfo()
        var got = 0
        TurtleUuids.TELEMETRY.forEachIndexed { i, uuid ->
            _handshake.update { it.copy(now = "Telemetry value ${i + 1} of ${TurtleUuids.TELEMETRY.size}") }
            read(TurtleUuids.TURTLE_SERVICE, uuid)?.let { apply(uuid, it); got++ }
        }
        if (!linkUp) return
        found(HandshakeStep.READ, (_telemetry.value.deviceInfo?.firmware?.let { "Firmware $it, " } ?: "") +
            "$got of ${TurtleUuids.TELEMETRY.size} values read")

        begin(HandshakeStep.SUBSCRIBE, "Turning on notifications")
        var on = TurtleUuids.TELEMETRY.count { enableNotify(TurtleUuids.TURTLE_SERVICE, it) }
        if (!readOnly && enableNotify(TurtleUuids.COMMAND_SERVICE, TurtleUuids.RESULT)) on++
        if (!linkUp) return
        found(HandshakeStep.SUBSCRIBE, "$on notifications on" + if (readOnly) ", no commands" else "")
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
        _handshake.update { it.copy(failed = reason) }
        _state.value = LinkState.Disconnected(reason)
        gatt?.disconnect()
    }

    private fun failPending() {
        pendingMtu?.complete(mtu)
        pendingDiscover?.complete(false)
        pendingRead?.complete(ReadReply(LINK_DROPPED, null))
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
                    retrySetup(status)
                    return
                }
                if (prior !is LinkState.Disconnected) {
                    val reason = if (status == BluetoothGatt.GATT_SUCCESS) null
                        else "Link lost: ${gattStatusName(status)}"
                    if (inSetup) _handshake.update { it.copy(failed = reason ?: "The turtle closed the connection") }
                    _state.value = LinkState.Disconnected(reason)
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
            pendingRead?.complete(ReadReply(status, value))
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (modern) return
            @Suppress("DEPRECATION")
            pendingRead?.complete(ReadReply(status, ch.value))
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
