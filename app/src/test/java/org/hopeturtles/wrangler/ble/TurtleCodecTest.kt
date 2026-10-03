package org.hopeturtles.wrangler.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoder tests against GATT contract v1. "Applemore" vectors are real bytes
 * read from turtle 18 in nRF Connect; the rest were packed with Python's
 * struct using the firmware's exact format strings.
 */
class TurtleCodecTest {

    private fun hex(s: String): ByteArray =
        s.replace("-", "").replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun near(expected: Double, actual: Double?, eps: Double = 1e-6) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, eps)
    }

    @Test fun status_applemore_unbonded() {
        val s = TurtleCodec.status(hex("F3-BF-02-00-01-00-78-00-00-00-00-00"))!!
        assertTrue(s.wifiConnected); assertTrue(s.apiOk); assertFalse(s.gpsFixed)
        assertTrue(s.as5600Present); assertTrue(s.servoPresent); assertTrue(s.rtcSynced)
        assertFalse(s.linkBonded); assertTrue(s.requireBond); assertTrue(s.wifiCredentialsSet)
        assertEquals(TelemetryMode.AUTO, s.telemetryMode)
        assertEquals(ConnectionMode.WIFI_AUTO, s.connectionMode)
        assertEquals(120, s.intervalS); assertEquals(0, s.queueCount); assertEquals(0, s.stampsSession)
    }

    @Test fun status_applemore_bonded() {
        val s = TurtleCodec.status(hex("F3-F7-02-00-01-00-78-00-00-00-00-00"))!!
        assertTrue(s.linkBonded)
        assertFalse(s.as5600Present)
    }

    @Test fun imu_applemore() {
        val i = TurtleCodec.imu(hex("43-01-FE-FA-17-28"))!!
        near(32.3, i.pitchDeg); near(-128.2, i.rollDeg); near(1026.3, i.pressureHpa)
    }

    @Test fun shore_applemore_no_journey() {
        val s = TurtleCodec.shore(hex("CE-6E-BF-6A-00-00-00-00-D8-6E-BF-6A"))!!
        assertEquals(1790930638L, s.lastShoreSync)
        assertNull(s.journeyId)
        assertEquals(1790930648L, s.deviceNow)
    }

    @Test fun position() {
        val p = TurtleCodec.position(hex("539f4f1e361928ff08010000"))!!
        near(50.8534611, p.lat); near(-1.4149322, p.lon)
        assertEquals(8, p.sats); assertEquals(1, p.fixQuality); assertEquals(0, p.fixAgeS)
        assertTrue(p.hasFix)
    }

    @Test fun nav_safe_gps_lost_no_xte() {
        val n = TurtleCodec.nav(hex("a909280275100000ff7f040200"))!!
        near(247.3, n.headingDeg); near(55.2, n.bearingToTargetDeg)
        assertEquals(4213L, n.distToTargetM)
        assertNull(n.xteM)                       // 0x7FFF sentinel: not computed yet
        assertEquals(NavState.SAFE, n.state); assertEquals(NavFault.GPS_LOST, n.fault)
        assertEquals(false, n.feathering)
    }

    @Test fun targets() {
        val t = TurtleCodec.targets(hex("c09e501e80602aff7c74ac12903f671402"))!!
        near(50.86, t.set!!.lat); near(-1.40, t.set!!.lon)
        near(31.32919, t.mission!!.lat); near(34.23108, t.mission!!.lon)
        assertEquals(TargetSource.SET_DESTINATION, t.activeSource)
        val none = TurtleCodec.targets(hex("ffffff7fffffff7f7c74ac12903f671404"))!!
        assertNull(none.set)
        assertEquals(TargetSource.MISSION_DESTINATION, none.activeSource)
    }

    @Test fun sail_sentinels() {
        val s = TurtleCodec.sail(hex("4001ffff00ff"))!!
        near(32.0, s.sailDeg); assertNull(s.windDeg); assertNull(s.confidencePct)
        assertEquals(SweepState.IDLE, s.sweep)
    }

    @Test fun power() {
        val p = TurtleCodec.power(hex("100e88ff21"))!!
        assertEquals(3600, p.voltageMv); assertEquals(-120, p.currentMa); assertEquals(33, p.socPct)
    }

    @Test fun contract_info_and_name() {
        assertEquals(ContractInfo(1, 1), TurtleCodec.contractInfo(hex("0101")))
        assertEquals("Applemore", TurtleCodec.turtleName("Applemore".toByteArray()))
    }

    @Test fun shorter_than_contract_is_rejected() {
        assertNull(TurtleCodec.position(ByteArray(11)))
        assertNull(TurtleCodec.nav(ByteArray(12)))
        assertNull(TurtleCodec.status(ByteArray(11)))
        assertNull(TurtleCodec.contractInfo(ByteArray(1)))
    }

    @Test fun longer_than_contract_is_accepted() {
        // Firmware may append fields within v1; extra bytes are ignored.
        val p = TurtleCodec.power(hex("100e88ff21" + "DEADBEEF"))!!
        assertEquals(3600, p.voltageMv)
    }
}
