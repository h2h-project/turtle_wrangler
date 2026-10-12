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

    @Test fun tz_labels_like_the_oled() {
        assertEquals("UTC", tzLabel(0))
        assertEquals("UTC+3", tzLabel(180))
        assertEquals("UTC+1", tzLabel(60))
        assertEquals("UTC−5:30", tzLabel(-330))
        assertEquals("UTC+5:45", tzLabel(345))
    }

    @Test fun clock_vs_phone_words() {
        assertEquals("In step with this phone", clockVsPhone(difference(1)))
        assertEquals("3 min behind this phone", clockVsPhone(difference(-200)))
        assertEquals("5 h ahead of this phone", clockVsPhone(difference(5 * 3_600 + 10)))
    }

    @Test fun interval_words() {
        assertEquals("2 min", Format.interval(120))
        assertEquals("1 h", Format.interval(3_600))
        assertEquals("45 s", Format.interval(45))
        assertEquals("90 s", Format.interval(90))
    }
}
