package org.hopeturtles.wrangler.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** turtleOS 2.6.0 journey values, as the firmware's own handlers packed them. */
class JourneyCodecTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun journeyCharacteristic() {
        val j = TurtleCodec.journey(hex("6093bf6a539f4f1e361928ff506cbf6a"))!!
        assertEquals(1790940000L, j.openId)
        assertEquals(50.8534611, j.departure!!.lat, 1e-7)
        assertEquals(-1.4149322, j.departure!!.lon, 1e-7)
        assertEquals(1790930000L, j.lastEndedId)
        assertNull(TurtleCodec.journey(ByteArray(15)))
        val none = TurtleCodec.journey(hex("00000000ffffff7fffffff7f00000000"))!!
        assertNull(none.openId); assertNull(none.departure); assertNull(none.lastEndedId)
    }

    @Test fun journeyListResult() {
        val (count, j) = TurtleCodec.journeyListResult(
            hex("0100506cbf6a607abf6a20184f1ee0d928ffc09e501e80602affffffff7fffffff7f02"))!!
        assertEquals(1, count)
        assertEquals(1790930000L, j.id)
        assertEquals(1790933600L, j.endedAt)
        assertEquals(LatLon(50.85, -1.41), j.departure)
        assertEquals(LatLon(50.86, -1.40), j.destination)
        assertNull(j.arrival)                                   // no fix at the end
        assertEquals(TargetSource.SET_DESTINATION, j.destinationSource)
    }

    @Test fun journeyTrackResult() {
        val p = TurtleCodec.journeyTrackResult(hex("02000000026f93bf6a20184f1ee0d928ff027e93bf6a303f4f1ef00029ff00"))!!
        assertEquals(2, p.total); assertEquals(0, p.start); assertEquals(2, p.n)
        assertEquals(TrackPoint(1790940015, 50.85, -1.41, NavState.SAIL_NAV), p.points[0])
        assertEquals(NavState.BOOT, p.points[1].state)
        assertNull(TurtleCodec.journeyTrackResult(hex("0200000002")))   // says 2 points, has none
    }

    @Test fun requests() {
        assertArrayEquals(byteArrayOf(3), Payloads.journeyList(3))
        assertArrayEquals(hex("6093bf6a0d00"), Payloads.journeyTrack(1790940000L, 13))
    }
}
