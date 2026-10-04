package org.hopeturtles.wrangler.ui

import org.hopeturtles.wrangler.ble.NavFault
import org.hopeturtles.wrangler.ble.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test fun heading_and_letters() {
        assertEquals("SW 247°", Format.heading(247.3))
        assertEquals("N 0°", Format.heading(359.8))     // rounds to 360 → shown as 0
        assertEquals("NE 28°", Format.heading(28.0))
        assertNull(Format.heading(null))
    }

    @Test fun distance() {
        assertEquals("420 m", Format.distance(420))
        assertEquals("4.2 km", Format.distance(4213))
        assertEquals("312 km", Format.distance(312_400))
        assertNull(Format.distance(null))
    }

    @Test fun turn_wraps_and_words() {
        assertEquals(4.0, Format.turn(247.0, 251.0)!!, 1e-9)
        assertEquals(-20.0, Format.turn(10.0, 350.0)!!, 1e-9)
        assertEquals(20.0, Format.turn(350.0, 10.0)!!, 1e-9)
        assertEquals("4° right", Format.turnText(4.0))
        assertEquals("12° left", Format.turnText(-12.0))
        assertEquals("on course", Format.turnText(1.4))
        assertNull(Format.turn(null, 10.0))
    }

    @Test fun power() {
        assertEquals("3.60 V", Format.volts(3600))
        assertEquals("120 mA", Format.current(120))
        assertEquals("85 mA", Format.current(-85))
    }

    @Test fun charge_state_negative_is_charging() {
        assertEquals(Format.Charge.CHARGING, Format.charge(-120))
        assertEquals(Format.Charge.DISCHARGING, Format.charge(85))
        assertEquals(Format.Charge.IDLE, Format.charge(3))
        assertEquals(Format.Charge.IDLE, Format.charge(-5))
        assertEquals("Charging", Format.chargeWord(Format.charge(-400)))
        assertNull(Format.charge(null))
    }

    @Test fun position_and_staleness() {
        val fresh = Position(50.8534611, -1.4149322, 8, 1, 2)
        val stale = fresh.copy(fixAgeS = 42)
        val none = Position(null, null, null, null, null)
        assertEquals("50.85346°N, 1.41493°W", Format.latLon(fresh.lat, fresh.lon))
        assertFalse(Format.fixStale(fresh)); assertTrue(Format.fixStale(stale)); assertTrue(Format.fixStale(none))
        assertEquals("Fixed · 8 sats", Format.fixSummary(fresh))
        assertEquals("Fix 42s old · 8 sats", Format.fixSummary(stale))
        assertEquals("No fix", Format.fixSummary(none))
    }

    @Test fun faults_in_plain_words() {
        assertEquals("GPS lost", Format.fault(NavFault.GPS_LOST))
        assertEquals("No waypoints configured", Format.fault(NavFault.NO_WAYPOINTS))
        assertNull(Format.fault(NavFault.NONE))
    }

    @Test fun xte() {
        assertEquals("on track", Format.xte(1))
        assertEquals("12 m right of track", Format.xte(12))
        assertEquals("7 m left of track", Format.xte(-7))
        assertNull(Format.xte(null))
    }
}
