package org.hopeturtles.wrangler.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vectors packed with Python's struct, matching device/src/net/ble_commands.py. */
class PayloadsTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun coords_e7() {
        // struct.pack("<ii", 508534611, -14149322)
        assertArrayEquals(hex("539f4f1e361928ff"), Payloads.coordsE7(50.8534611, -1.4149322))
        assertNull(Payloads.coordsE7(91.0, 0.0))
        assertNull(Payloads.coordsE7(0.0, -180.5))
        assertNull(Payloads.coordsE7(Double.NaN, 0.0))
    }

    @Test fun result_payloads() {
        assertEquals(1725800000L, Payloads.journeyId(hex("409edd66")))   // struct.pack("<I", 1725800000)
        assertEquals(4000000000L, Payloads.journeyId(hex("00286bee")))  // > i32 max stays positive
        assertNull(Payloads.journeyId(ByteArray(3)))
        assertEquals(true, Payloads.arrivalStamped(hex("01")))
        assertEquals(false, Payloads.arrivalStamped(hex("00")))
        assertNull(Payloads.arrivalStamped(ByteArray(0)))
        val p = Payloads.latLon(hex("539f4f1e361928ff"))!!
        assertEquals(50.8534611, p.lat, 1e-9); assertEquals(-1.4149322, p.lon, 1e-9)
    }

    @Test fun u16() {
        assertArrayEquals(hex("7800"), Payloads.u16(120))
        assertArrayEquals(hex("b4"), Payloads.servoAngle(180))
        assertArrayEquals(hex("00"), Payloads.servoAngle(-5))
        assertArrayEquals(hex("b4"), Payloads.servoAngle(200))
    }
}
