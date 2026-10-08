package org.hopeturtles.wrangler.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTest {
    @Test fun difference_words() {
        assertEquals("in step", difference(0))
        assertEquals("in step", difference(-2))
        assertEquals("45 s ahead", difference(45))
        assertEquals("3 min behind", difference(-200))
        assertEquals("5 h ahead", difference(5 * 3_600 + 10))
        assertEquals("12 days behind", difference(-12L * 86_400))
        // a turtle whose RTC restarted at 2000-01-01, seen on 8 Oct 2026
        assertEquals("26 years behind", difference(946_690_000L - 1_791_475_200L))
    }
}
