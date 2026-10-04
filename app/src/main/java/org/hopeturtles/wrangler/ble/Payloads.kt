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

    /** TELEMETRY_SET_INTERVAL `<H>` seconds. */
    fun u16(v: Int): ByteArray = le(2).putShort(v.toShort()).array()

    /** JOURNEY_START OK → `<I>` journey_id (unix seconds). */
    fun journeyId(p: ByteArray): Long? = if (p.size < 4) null else rd(p).int.toLong() and 0xFFFF_FFFFL

    /** JOURNEY_END OK → `<B>` arrival_stamped (false = no fix; the journey still ended). */
    fun arrivalStamped(p: ByteArray): Boolean? = if (p.isEmpty()) null else p[0].toInt() != 0

    /** DEST_SET_HERE OK → `<ii>` the stamped lat/lon × 1e7. */
    fun latLon(p: ByteArray): LatLon? {
        if (p.size < 8) return null
        val b = rd(p)
        return LatLon(b.int / 1e7, b.int / 1e7)
    }
}
