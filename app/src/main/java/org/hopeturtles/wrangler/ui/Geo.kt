package org.hopeturtles.wrangler.ui

import org.hopeturtles.wrangler.ble.LatLon
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geodesy helpers for the Navigate tab. Pure; unit-tested. */
object Geo {
    private const val EARTH_M = 6_371_000.0

    /** Great-circle distance in whole metres (haversine — same as the firmware). */
    fun distanceM(a: LatLon?, b: LatLon?): Long? {
        if (a == null || b == null) return null
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return (2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))).roundToLong()
    }
}
