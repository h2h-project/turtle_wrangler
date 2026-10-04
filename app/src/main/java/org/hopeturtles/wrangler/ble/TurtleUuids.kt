package org.hopeturtles.wrangler.ble

import java.util.UUID

/**
 * GATT contract v1 (turtleOS repo: docs/wrangler/README.md — the source of
 * truth; change it there first). Custom UUIDs share one random base with the
 * 16-bit short id in the first group: 26d0XXXX-5890-45f2-b0be-090d35436a95.
 */
object TurtleUuids {
    const val CONTRACT_VERSION = 1

    fun of(short: Int): UUID =
        UUID.fromString("26d0%04x-5890-45f2-b0be-090d35436a95".format(short))

    // Services
    val TURTLE_SERVICE: UUID = of(0x0001)    // advertised; the scanner filters on it
    val COMMAND_SERVICE: UUID = of(0x0002)

    // Turtle service characteristics
    val CONTRACT_INFO: UUID = of(0x0101)
    val TURTLE_NAME: UUID = of(0x0102)
    val POSITION: UUID = of(0x0110)
    val NAV: UUID = of(0x0111)
    val TARGETS: UUID = of(0x0112)
    val SAIL: UUID = of(0x0113)
    val POWER: UUID = of(0x0114)
    val IMU: UUID = of(0x0115)
    val STATUS: UUID = of(0x0116)
    val SHORE: UUID = of(0x0117)
    /** Added within v1 (2026-10-04): optional — older firmware lacks it. */
    val ENVIRONMENT: UUID = of(0x0118)

    /** The read + notify characteristics, in contract order. Missing ones
     *  (e.g. ENVIRONMENT on older firmware) are skipped by the connection. */
    val TELEMETRY = listOf(POSITION, NAV, TARGETS, SAIL, POWER, IMU, STATUS, SHORE, ENVIRONMENT)

    // Command service
    val COMMAND: UUID = of(0x0201)
    val RESULT: UUID = of(0x0202)

    // Standard Device Information service 0x180A
    private fun sig(short: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    val DEVICE_INFO_SERVICE: UUID = sig(0x180A)
    val MANUFACTURER: UUID = sig(0x2A29)
    val MODEL: UUID = sig(0x2A24)
    val FIRMWARE: UUID = sig(0x2A26)
    val SERIAL: UUID = sig(0x2A25)

    /** Client Characteristic Configuration descriptor (enable notifications). */
    val CCCD: UUID = sig(0x2902)
}
