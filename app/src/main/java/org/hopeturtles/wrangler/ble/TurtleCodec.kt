package org.hopeturtles.wrangler.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Decoders for the Turtle service characteristics — GATT contract v1.
 * Mirrors turtleOS device/src/net/ble_telemetry.py byte for byte.
 *
 * Contract rules applied everywhere:
 *   - little-endian, packed;
 *   - a value SHORTER than the v1 length is rejected (decoder returns null);
 *     a LONGER one is accepted and the extra bytes ignored (firmware may
 *     append fields within v1);
 *   - sentinels mean "no data" and become null — never shown as a number.
 */

private const val NA_I32 = 0x7FFFFFFF
private const val NA_U16 = 0xFFFF
private const val NA_I16 = 0x7FFF
private const val NA_U32 = 0xFFFFFFFFL
private const val NA_U8 = 0xFF

private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
private fun ByteBuffer.i16(): Int = short.toInt()
private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL

private fun e7(v: Int): Double? = if (v == NA_I32) null else v / 1e7
private fun x10u(v: Int): Double? = if (v == NA_U16) null else v / 10.0
private fun x10i(v: Int): Double? = if (v == NA_I16) null else v / 10.0
private fun u8OrNull(v: Int): Int? = if (v == NA_U8) null else v
private fun u16OrNull(v: Int): Int? = if (v == NA_U16) null else v

data class ContractInfo(val contractVersion: Int, val turtleMode: Int)

data class Position(
    val lat: Double?, val lon: Double?,
    val sats: Int?, val fixQuality: Int?, val fixAgeS: Int?,
) {
    val hasFix: Boolean get() = lat != null && lon != null && fixAgeS != null
}

enum class NavState { BOOT, ACQUIRE, SAIL_NAV, ARRIVAL, SAFE, UNKNOWN }
enum class NavFault { NONE, GPS_NEVER_ACQUIRED, GPS_LOST, NO_WAYPOINTS, OTHER }

data class Nav(
    val headingDeg: Double?, val bearingToTargetDeg: Double?, val distToTargetM: Long?,
    val xteM: Int?, val state: NavState, val fault: NavFault?, val feathering: Boolean?,
)

enum class TargetSource { NONE, SET_WAYPOINTS, SET_DESTINATION, MISSION_WAYPOINTS, MISSION_DESTINATION, UNKNOWN }

data class LatLon(val lat: Double, val lon: Double)

data class Targets(val set: LatLon?, val mission: LatLon?, val activeSource: TargetSource)

enum class SweepState { IDLE, NAV_SWEEP, BENCH_SWEEP, UNKNOWN }

/**
 * [servoPosDeg]: last commanded servo position, 0–180 of its full travel
 * (autopilot or the dial); [manualHoldS]: seconds of hand control left.
 * Both null on firmware before 2026-10-05 (6-byte Sail).
 */
data class Sail(
    val sailDeg: Double?, val windDeg: Double?, val sweep: SweepState, val confidencePct: Int?,
    val servoPosDeg: Double? = null, val manualHoldS: Int? = null,
)

data class Power(val voltageMv: Int?, val currentMa: Int?, val socPct: Int?)

data class Imu(val pitchDeg: Double?, val rollDeg: Double?, val pressureHpa: Double?)

enum class TelemetryMode { OFF, AUTO, MANUAL, UNKNOWN }
enum class ConnectionMode { WIFI_AUTO, WIFI_MANUAL, LORA, UNKNOWN }

data class Status(
    val flags: Long,
    val telemetryMode: TelemetryMode,
    val connectionMode: ConnectionMode,
    val intervalS: Int?,
    val queueCount: Int?,
    val stampsSession: Int?,
) {
    private fun bit(n: Int) = (flags shr n) and 1L == 1L
    val wifiConnected get() = bit(0)
    val apiOk get() = bit(1)
    val gpsFixed get() = bit(2)
    val journeyActive get() = bit(3)
    val wifiEnabled get() = bit(4)
    val gpsEnabled get() = bit(5)
    val gpsHwPresent get() = bit(6)
    val imuPresent get() = bit(7)
    val magPresent get() = bit(8)
    val ina219Present get() = bit(9)
    val baroPresent get() = bit(10)
    val as5600Present get() = bit(11)
    val servoPresent get() = bit(12)
    val rtcSynced get() = bit(13)
    val linkBonded get() = bit(14)
    val requireBond get() = bit(15)
    val commandInProgress get() = bit(16)
    val wifiCredentialsSet get() = bit(17)
    // added 2026-10-08 (turtleOS 2.5.1); older firmware sends 0
    val rtcBatteryFault get() = bit(18)
    val secureMode get() = bit(19)
    val secureModeBlocked get() = bit(20)
}

/** Conditions inside the control bottle (0118). */
data class Environment(
    val airTempC: Double?,     // AHT21 — the headline "bottle temperature"
    val humidityPct: Double?,  // AHT21 relative humidity
    val baroTempC: Double?,    // BMP180
    val boardTempC: Double?,   // DS3231 die temperature
    val pressureHpa: Double?,  // BMP180
)

/**
 * Times are unix seconds; null = never / none / clock not set.
 * [deviceClock]: the turtle's clock whatever it says, even unsynced (e.g.
 * 2000-01-01 after the RTC lost power); null on firmware before 2026-10-08
 * (12-byte Shore) or when it can't be read.
 * [clockSource]: what set the clock this boot; null before turtleOS 2.5.1
 * (16-byte Shore).
 */
data class Shore(
    val lastShoreSync: Long?, val journeyId: Long?, val deviceNow: Long?,
    val deviceClock: Long? = null,
    val clockSource: ClockSource? = null,
)

/** Shore `clock_source`. */
enum class ClockSource(val label: String) {
    NONE("Not set"), RTC("Clock chip"), NTP("Internet (NTP)"), GPS("GPS"), PHONE("A phone"), UNKNOWN("—");

    companion object {
        fun of(v: Int) = entries.getOrNull(v)?.takeIf { it != UNKNOWN } ?: UNKNOWN
    }
}

object TurtleCodec {
    fun contractInfo(b: ByteArray): ContractInfo? {
        if (b.size < 2) return null
        val x = le(b)
        return ContractInfo(x.u8(), x.u8())
    }

    fun turtleName(b: ByteArray): String = b.toString(Charsets.UTF_8)

    fun position(b: ByteArray): Position? {
        if (b.size < 12) return null
        val x = le(b)
        return Position(e7(x.int), e7(x.int), u8OrNull(x.u8()), u8OrNull(x.u8()), u16OrNull(x.u16()))
    }

    fun nav(b: ByteArray): Nav? {
        if (b.size < 13) return null
        val x = le(b)
        val heading = x10u(x.u16())
        val bearing = x10u(x.u16())
        val dist = x.u32().let { if (it == NA_U32) null else it }
        val xte = x.i16().let { if (it == NA_I16) null else it }   // whole metres, + = right of track
        val st = x.u8()
        val fault = x.u8()
        val trim = x.u8()
        return Nav(
            headingDeg = heading,
            bearingToTargetDeg = bearing,
            distToTargetM = dist,
            xteM = xte,
            state = NavState.entries.getOrElse(st) { NavState.UNKNOWN },
            fault = when (fault) {
                0 -> NavFault.NONE; 1 -> NavFault.GPS_NEVER_ACQUIRED; 2 -> NavFault.GPS_LOST
                3 -> NavFault.NO_WAYPOINTS; NA_U8 -> null; else -> NavFault.OTHER
            },
            feathering = when (trim) { 0 -> false; 1 -> true; else -> null },
        )
    }

    fun targets(b: ByteArray): Targets? {
        if (b.size < 17) return null
        val x = le(b)
        val sLat = e7(x.int); val sLon = e7(x.int)
        val mLat = e7(x.int); val mLon = e7(x.int)
        val src = x.u8()
        return Targets(
            set = if (sLat != null && sLon != null) LatLon(sLat, sLon) else null,
            mission = if (mLat != null && mLon != null) LatLon(mLat, mLon) else null,
            activeSource = TargetSource.entries.getOrElse(src) { TargetSource.UNKNOWN },
        )
    }

    fun sail(b: ByteArray): Sail? {
        if (b.size < 6) return null
        val x = le(b)
        val sail = x10u(x.u16())
        val wind = x10u(x.u16())
        val sweep = SweepState.entries.getOrElse(x.u8()) { SweepState.UNKNOWN }
        val conf = u8OrNull(x.u8())
        if (b.size < 9) return Sail(sail, wind, sweep, conf)
        return Sail(sail, wind, sweep, conf, x10u(x.u16()), u8OrNull(x.u8()))
    }

    fun power(b: ByteArray): Power? {
        if (b.size < 5) return null
        val x = le(b)
        val mv = u16OrNull(x.u16())
        val ma = x.i16().let { if (it == NA_I16) null else it }
        return Power(mv, ma, u8OrNull(x.u8()))
    }

    fun imu(b: ByteArray): Imu? {
        if (b.size < 6) return null
        val x = le(b)
        return Imu(x10i(x.i16()), x10i(x.i16()), x10u(x.u16()))
    }

    fun status(b: ByteArray): Status? {
        if (b.size < 12) return null
        val x = le(b)
        val flags = x.u32()
        val mode = x.u8()
        val conn = x.u8()
        return Status(
            flags = flags,
            telemetryMode = TelemetryMode.entries.getOrElse(mode) { TelemetryMode.UNKNOWN },
            connectionMode = ConnectionMode.entries.getOrElse(conn) { ConnectionMode.UNKNOWN },
            intervalS = u16OrNull(x.u16()),
            queueCount = u16OrNull(x.u16()),
            stampsSession = u16OrNull(x.u16()),
        )
    }

    fun environment(b: ByteArray): Environment? {
        if (b.size < 10) return null
        val x = le(b)
        return Environment(x10i(x.i16()), x10u(x.u16()), x10i(x.i16()), x10i(x.i16()), x10u(x.u16()))
    }

    fun shore(b: ByteArray): Shore? {
        if (b.size < 12) return null
        val x = le(b)
        fun t(v: Long) = if (v == 0L) null else v
        val s = Shore(t(x.u32()), t(x.u32()), t(x.u32()))
        if (b.size < 16) return s
        val withClock = s.copy(deviceClock = t(x.u32()))
        return if (b.size < 17) withClock else withClock.copy(clockSource = ClockSource.of(x.u8()))
    }
}
