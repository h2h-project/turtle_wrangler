package org.hopeturtles.wrangler.ui

import org.hopeturtles.wrangler.ble.NavFault
import org.hopeturtles.wrangler.ble.NavState
import org.hopeturtles.wrangler.ble.Position
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Display formatting. Pure functions (unit-tested). Every function returns
 * null for missing data; the UI shows null as "—".
 */
object Format {

    /** Freshness limits for live data (app plan Phase 2). */
    const val FIX_STALE_S = 10
    const val LIVE_STALE_MS = 15_000L

    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compassLetters(deg: Double): String = COMPASS[(((deg % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8]

    /** "247°" */
    fun degrees(deg: Double?): String? = deg?.let { "${it.roundToInt() % 360}°" }

    /** "SW 247°" — the waiting-screen style. */
    fun heading(deg: Double?): String? = deg?.let { "${compassLetters(it)} ${it.roundToInt() % 360}°" }

    /** 420 m · 4.2 km · 312 km */
    fun distance(m: Long?): String? = m?.let {
        when {
            it < 1_000 -> "$it m"
            it < 100_000 -> "%.1f km".format(it / 1000.0)
            else -> "${(it / 1000.0).roundToInt()} km"
        }
    }

    /** Signed turn from heading to bearing, -180..180 (+ = turn right). */
    fun turn(headingDeg: Double?, bearingDeg: Double?): Double? {
        if (headingDeg == null || bearingDeg == null) return null
        var d = (bearingDeg - headingDeg) % 360.0
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /** "on course" · "4° right" · "12° left" */
    fun turnText(turnDeg: Double?): String? = turnDeg?.let {
        val n = abs(it).roundToInt()
        when {
            n <= 2 -> "on course"
            it > 0 -> "$n° right"
            else -> "$n° left"
        }
    }

    fun volts(mv: Int?): String? = mv?.let { "%.2f V".format(it / 1000.0) }

    /** Below this magnitude the pack is neither charging nor discharging. */
    const val IDLE_MA = 5

    enum class Charge { CHARGING, DISCHARGING, IDLE }

    /** Contract v1: current_mA is the raw INA219 sign — negative = charging. */
    fun charge(ma: Int?): Charge? = ma?.let {
        when {
            it < -IDLE_MA -> Charge.CHARGING
            it > IDLE_MA -> Charge.DISCHARGING
            else -> Charge.IDLE
        }
    }

    fun chargeWord(c: Charge?): String? = when (c) {
        Charge.CHARGING -> "Charging"
        Charge.DISCHARGING -> "Discharging"
        Charge.IDLE -> "Idle"
        null -> null
    }

    /** Magnitude only — the Charging / Discharging word carries the direction. */
    fun current(ma: Int?): String? = ma?.let { "${kotlin.math.abs(it)} mA" }

    fun latLon(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return "%.5f°%s, %.5f°%s".format(abs(lat), ns, abs(lon), ew)
    }

    /** True when the position should be greyed out (old or missing fix). */
    fun fixStale(p: Position?): Boolean = p?.fixAgeS == null || p.fixAgeS > FIX_STALE_S

    fun fixSummary(p: Position?): String? {
        if (p == null) return null
        if (p.fixAgeS == null) return "No fix"
        val sats = p.sats?.let { "$it sats" } ?: "— sats"
        return if (p.fixAgeS > FIX_STALE_S) "Fix ${p.fixAgeS}s old · $sats" else "Fixed · $sats"
    }

    fun navState(s: NavState?): String? = when (s) {
        NavState.BOOT -> "Starting up"
        NavState.ACQUIRE -> "Acquiring"
        NavState.SAIL_NAV -> "Sailing to target"
        NavState.ARRIVAL -> "Arrived"
        NavState.SAFE -> "SAFE mode"
        NavState.UNKNOWN, null -> null
    }

    /** Plain words for why the turtle is in SAFE (app plan Phase 2). */
    fun fault(f: NavFault?): String? = when (f) {
        NavFault.GPS_NEVER_ACQUIRED -> "GPS never got a fix"
        NavFault.GPS_LOST -> "GPS lost"
        NavFault.NO_WAYPOINTS -> "No waypoints configured"
        NavFault.OTHER -> "Fault (see the turtle's serial log)"
        NavFault.NONE, null -> null
    }
}
