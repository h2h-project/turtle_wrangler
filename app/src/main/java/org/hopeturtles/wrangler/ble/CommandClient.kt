package org.hopeturtles.wrangler.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** GATT contract v1 opcodes (turtleOS docs/wrangler/README.md → Opcodes). */
object Op {
    const val JOURNEY_START = 0x01
    const val JOURNEY_END = 0x02
    const val DEST_SET_HERE = 0x03
    const val DEST_SET_MISSION = 0x04
    const val DEST_SET_COORDS = 0x05
    const val DEST_CLEAR = 0x06
    const val NAV_LUFF_SWEEP = 0x10
    const val SERVO_BENCH_SWEEP = 0x11
    const val GPS_STAMP = 0x20
    const val GPS_SET_ENABLED = 0x21
    const val TELEMETRY_SET_MODE = 0x22
    const val TELEMETRY_SET_INTERVAL = 0x23
    const val API_HANDSHAKE = 0x24
    const val WIFI_SET_ENABLED = 0x30
    const val WIFI_SET_CREDENTIALS = 0x31
    const val CONNECTION_MODE_SET = 0x32
    const val BLE_SET_ENABLED = 0x33
    const val SLEEP = 0x40
    const val SET_TURTLE_MODE = 0x41
    const val REBOOT = 0x42
    const val COMPASS_SET_OFFSET = 0x43
}

/** GATT contract v1 result codes, each with the one sentence the app shows. */
enum class ResultCode(val code: Int, val message: String) {
    OK(0x00, "Done."),
    IN_PROGRESS(0x01, "Working on it…"),
    UNKNOWN_OPCODE(0x10, "This turtle's firmware doesn't know that command — it may need updating."),
    BAD_LENGTH(0x11, "The turtle rejected a malformed command."),
    BAD_VALUE(0x12, "That value is out of range."),
    BUSY(0x13, "The turtle is busy with another command. Try again in a moment."),
    NOT_BONDED(0x14, "Pair with the turtle first: open its Bluetooth screen and enter the 6-digit code it shows."),
    WRONG_STATE(0x15, "That isn't possible right now."),
    NO_GPS_FIX(0x20, "The turtle doesn't have a GPS fix yet. Try again in the open."),
    RTC_NOT_SYNCED(0x21, "The turtle's clock isn't set yet."),
    NO_HARDWARE(0x22, "The turtle is missing the hardware for that."),
    NO_TARGET(0x23, "No mission target is stored on the turtle."),
    WIFI_FAILED(0x30, "The turtle couldn't join the WiFi network."),
    API_FAILED(0x31, "The turtle couldn't reach hopeturtles.org."),
    NOT_STAMPED(0x32, "Nothing was recorded."),
    INTERNAL(0x7F, "Something went wrong on the turtle (see its serial log)."),
    TIMEOUT(-1, "The turtle didn't answer in time."),
    NOT_CONNECTED(-2, "Not connected to a turtle."),
    UNRECOGNISED(-3, "Failed.");

    val ok: Boolean get() = this == OK

    companion object {
        fun of(code: Int): ResultCode = entries.firstOrNull { it.code == code } ?: UNRECOGNISED
    }
}

/** A command's final outcome. [rawCode] keeps unknown codes for display ("Failed (0x5A)"). */
data class CommandResult(val code: ResultCode, val payload: ByteArray, val rawCode: Int) {
    val message: String
        get() = if (code == ResultCode.UNRECOGNISED) "Failed (0x%02X).".format(rawCode) else code.message
}

/**
 * Sends contract v1 commands over [TurtleConnection] and waits for the
 * matching Result (by seq). One command at a time, matching the firmware.
 *
 *   - 5 s to hear anything; after IN_PROGRESS, up to 180 s (a bench sweep can
 *     take minutes);
 *   - every outcome maps to a [ResultCode] with a human sentence — nothing
 *     fails silently.
 */
class CommandClient(private val link: TurtleConnection) {
    private val mutex = Mutex()
    private var seq = 0
    private var waitingSeq = -1
    private var inProgress: CompletableDeferred<Unit>? = null
    private var final: CompletableDeferred<ByteArray>? = null

    /** Opcode currently IN_PROGRESS, for spinners. */
    private val _progress = MutableStateFlow<Int?>(null)
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    init {
        link.onResult = ::onResult
    }

    private fun onResult(v: ByteArray) {
        if (v.size < 3) return
        val s = v[1].toInt() and 0xFF
        if (s != waitingSeq) return                  // a stale result from an earlier command
        val code = v[2].toInt() and 0xFF
        if (code == ResultCode.IN_PROGRESS.code) inProgress?.complete(Unit)
        else final?.complete(v)
    }

    suspend fun send(opcode: Int, payload: ByteArray = ByteArray(0)): CommandResult = mutex.withLock {
        seq = (seq + 1) and 0xFF
        val mySeq = seq
        waitingSeq = mySeq
        val started = CompletableDeferred<Unit>().also { inProgress = it }
        val done = CompletableDeferred<ByteArray>().also { final = it }

        val frame = byteArrayOf(opcode.toByte(), mySeq.toByte()) + payload
        if (!link.write(TurtleUuids.COMMAND_SERVICE, TurtleUuids.COMMAND, frame)) {
            return@withLock CommandResult(ResultCode.NOT_CONNECTED, ByteArray(0), -2)
        }

        // Phase 1: a final result, or IN_PROGRESS, within 5 s.
        var result = withTimeoutOrNull(5_000) {
            kotlinx.coroutines.selects.select<ByteArray?> {
                done.onAwait { it }
                started.onAwait { null }
            }
        }
        if (result == null && started.isCompleted) {
            _progress.value = opcode
            result = withTimeoutOrNull(180_000) { done.await() }
            _progress.value = null
        }
        waitingSeq = -1
        if (result == null) return@withLock CommandResult(ResultCode.TIMEOUT, ByteArray(0), -1)
        val raw = result[2].toInt() and 0xFF
        CommandResult(ResultCode.of(raw), result.copyOfRange(3, result.size), raw)
    }
}
