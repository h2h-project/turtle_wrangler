package org.hopeturtles.wrangler.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Command payloads and the OK-result payloads the app reads back
 * (GATT contract v1 → Opcodes). Little-endian, like everything else.
 */
object Payloads {
    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun rd(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    /** DEST_SET_COORDS `<ii>` lat/lon × 1e7. Null if out of range (the turtle would say BAD_VALUE). */
    fun coordsE7(lat: Double, lon: Double): ByteArray? {
        if (lat.isNaN() || lon.isNaN() || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return le(8).putInt((lat * 1e7).roundToInt()).putInt((lon * 1e7).roundToInt()).array()
    }

    /** SERVO_SET_ANGLE `<B>` 0..180 (clamped). */
    fun servoAngle(deg: Int): ByteArray = byteArrayOf(deg.coerceIn(0, 180).toByte())

    /** TIME_SET `<I>` unix seconds, UTC. */
    fun unixTime(s: Long): ByteArray = le(4).putInt(s.toInt()).array()

    /** TIME_SET OK → `<B>` rtc_chip_written (false = system clock only, lost at the next boot). */
    fun rtcChipWritten(p: ByteArray): Boolean? = if (p.isEmpty()) null else p[0].toInt() != 0

    /** SECURE_MODE_SET WRONG_STATE → `<B>` 1: turning on refused, RTC battery fault. */
    fun secureRefusedForBattery(p: ByteArray): Boolean = p.isNotEmpty() && p[0].toInt() == 1

    /** TELEMETRY_SET_INTERVAL `<H>` seconds. */
    fun u16(v: Int): ByteArray = le(2).putShort(v.toShort()).array()

    /** JOURNEY_START OK → `<I>` journey_id (unix seconds). */
    fun journeyId(p: ByteArray): Long? = if (p.size < 4) null else rd(p).int.toLong() and 0xFFFF_FFFFL

    /** JOURNEY_END OK → `<B>` arrival_stamped (false = no fix; the journey still ended). */
    fun arrivalStamped(p: ByteArray): Boolean? = if (p.isEmpty()) null else p[0].toInt() != 0

    /** GPS_STAMP OK → `<BHB>` committed_to, stamps_session, has_position. */
    fun stampOk(p: ByteArray): StampOk? {
        if (p.size < 4) return null
        val b = rd(p)
        val to = b.get().toInt() and 0xFF
        val n = b.short.toInt() and 0xFFFF
        return StampOk(queued = to == 0, stampsSession = n, hasPosition = (b.get().toInt() and 0xFF) != 0)
    }

    /** GPS_STAMP NOT_STAMPED → `<B>` reason. */
    fun notStampedReason(p: ByteArray): NotStamped =
        when (if (p.isEmpty()) -1 else p[0].toInt() and 0xFF) {
            1 -> NotStamped.CLOCK_NOT_SET
            2 -> NotStamped.GPS_OFF_NO_VALUES
            3 -> NotStamped.NO_FIX_NO_VALUES
            else -> NotStamped.OTHER
        }

    /** DEST_SET_HERE OK → `<ii>` the stamped lat/lon × 1e7. */
    fun latLon(p: ByteArray): LatLon? {
        if (p.size < 8) return null
        val b = rd(p)
        return LatLon(b.int / 1e7, b.int / 1e7)
    }
}

/**
 * A GPS_STAMP that was recorded. [queued]: committed to the flash queue
 * (offline) rather than handed to the background sender. [hasPosition]
 * false: recorded without a fix (sensor values and time only).
 */
data class StampOk(val queued: Boolean, val stampsSession: Int, val hasPosition: Boolean)

/** Why GPS_STAMP recorded nothing (NOT_STAMPED payload, contract v1). */
enum class NotStamped(val why: String) {
    CLOCK_NOT_SET("turtle clock not set"),
    GPS_OFF_NO_VALUES("GPS is off and there are no sensor values"),
    NO_FIX_NO_VALUES("no GPS fix and no sensor values"),
    OTHER("the turtle was busy sampling — try again"),
}
