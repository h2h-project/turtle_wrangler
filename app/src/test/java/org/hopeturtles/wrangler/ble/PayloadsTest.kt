package org.hopeturtles.wrangler.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun gps_stamp() {
        // struct.pack("<BHB", 0, 7, 1): queued, 7th stamp this boot, with a position
        assertEquals(StampOk(queued = true, stampsSession = 7, hasPosition = true), Payloads.stampOk(hex("00070001")))
        // struct.pack("<BHB", 1, 300, 0): handed to the sender, no fix
        assertEquals(StampOk(queued = false, stampsSession = 300, hasPosition = false), Payloads.stampOk(hex("012c0100")))
        assertNull(Payloads.stampOk(hex("000700")))
        assertEquals(NotStamped.CLOCK_NOT_SET, Payloads.notStampedReason(hex("01")))
        assertEquals(NotStamped.NO_FIX_NO_VALUES, Payloads.notStampedReason(hex("03")))
        assertEquals(NotStamped.OTHER, Payloads.notStampedReason(hex("ff")))
        assertEquals(NotStamped.OTHER, Payloads.notStampedReason(ByteArray(0)))
    }

    @Test fun time_set() {
        assertArrayEquals(hex("8002e668"), Payloads.unixTime(1759904384))   // struct.pack("<I", 1759904384)
        assertArrayEquals(hex("00286bee"), Payloads.unixTime(4000000000))   // past i32 max
        assertEquals(true, Payloads.rtcChipWritten(hex("01")))
        assertEquals(false, Payloads.rtcChipWritten(hex("00")))
        assertNull(Payloads.rtcChipWritten(ByteArray(0)))
    }

    @Test fun tz_offset() {
        assertArrayEquals(hex("3c00"), Payloads.tzOffset(60))      // struct.pack("<h", 60)
        assertArrayEquals(hex("b6fe"), Payloads.tzOffset(-330))
    }

    @Test fun secure_mode_refused() {
        assertTrue(Payloads.secureRefusedForBattery(hex("01")))
        assertFalse(Payloads.secureRefusedForBattery(hex("FF")))
        assertFalse(Payloads.secureRefusedForBattery(ByteArray(0)))
    }

    @Test fun u16() {
        assertArrayEquals(hex("7800"), Payloads.u16(120))
        assertArrayEquals(hex("b4"), Payloads.servoAngle(180))
        assertArrayEquals(hex("00"), Payloads.servoAngle(-5))
        assertArrayEquals(hex("b4"), Payloads.servoAngle(200))
    }
}
