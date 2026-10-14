package org.hopeturtles.wrangler.data

import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.ble.TargetSource
import org.hopeturtles.wrangler.ble.TrackPoint
import org.hopeturtles.wrangler.ble.TurtleJourney
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyRecordTest {
    private val a = LatLon(0.0, 0.0)
    private val b = LatLon(0.001, 0.0)        // ≈ 111 m north of a
    private val c = LatLon(0.002, 0.0)

    @Test fun pointsBelowTheStepAreSkipped() {
        val r = JourneyRecord(1_000).withPoint(a, 1_010, 5, 10)!!
        assertNull(r.withPoint(LatLon(0.00001, 0.0), 1_020, 5, 10))   // ≈ 1 m
        assertEquals(2, r.withPoint(b, 1_020, 5, 10)!!.track.size)
    }

    @Test fun aFullTrackIsThinnedNotCut() {
        var r = JourneyRecord(1_000)
        repeat(4) { i -> r = r.withPoint(LatLon(i * 0.001, 0.0), 1_000L + i, 5, 4)!! }
        r = r.withPoint(LatLon(0.01, 0.0), 1_010, 5, 4)!!
        assertEquals(listOf(0.0, 0.002, 0.01), r.track.map { it.p.lat })
    }

    @Test fun distances() {
        val r = JourneyRecord(1_000, departure = a, destination = c, track = listOf(RoutePoint(1_500, b)),
            arrival = b, endedAt = 4_600, endSeen = true)
        assertEquals(222L, r.plannedM)
        assertEquals(111L, r.travelledM)        // a → b → b
        assertEquals(111L, r.missM)
        assertEquals(3_600L, r.durationS)
    }

    @Test fun startedOnTheTurtleStartsAtTheFirstPointSeen() {
        val r = JourneyRecord(1_000, destination = c, track = listOf(RoutePoint(1_100, b)))
        assertEquals(b, r.start)
        assertEquals(111L, r.plannedM)
        assertNull(r.missM)                     // still open
    }

    @Test fun anUnseenEndHasOnlyALowerBound() {
        val r = JourneyRecord(1_000, lastSeenAt = 1_600, endedAt = 9_000, endSeen = false)
        assertNull(r.durationS)
        assertEquals(600L, r.minDurationS)
        assertNotNull(r.endedAt)
    }

    // ---- Phase E: the turtle's record merged in ----------------------------

    @Test fun theTurtlesFactsBeatThePhonesGuesses() {
        // The phone saw it start on the OLED (no departure), and noticed late that it had ended.
        val phone = JourneyRecord(1_000, destination = a, track = listOf(RoutePoint(1_100, b)),
            lastSeenAt = 1_600, endedAt = 9_000, endSeen = false)
        val t = TurtleJourney(1_000, endedAt = 4_600, departure = a, destination = c, arrival = c,
            destinationSource = TargetSource.SET_DESTINATION)
        val r = phone.withTurtle(t)
        assertTrue(r.turtle && r.departureFromTurtle && r.endSeen)
        assertEquals(4_600L, r.endedAt)
        assertEquals(3_600L, r.durationS)
        assertEquals(a, r.departure)
        assertEquals(c, r.destination)
        assertEquals(c, r.arrival)
        assertEquals(1, r.track.size)           // the phone's route stays
    }

    @Test fun aJourneyThePhoneNeverSaw() {
        val r = JourneyRecord(1_000).withTurtle(TurtleJourney(1_000, 2_800, a, c, null, null))
        assertTrue(r.ended && r.endSeen)
        assertEquals(1_800L, r.durationS)
        assertNull(r.arrival)                   // the turtle had no fix at the end
    }

    @Test fun theTurtleLostItsClock() {
        val phone = JourneyRecord(1_000, lastSeenAt = 1_600, endedAt = 9_000)
        val r = phone.withTurtle(TurtleJourney(1_000, null, a, c, null, null))
        assertEquals(9_000L, r.endedAt)         // the phone's estimate stays
        assertFalse(r.endSeen)
    }

    @Test fun turtlePointsJoinThePhonesInTimeOrder() {
        val phone = JourneyRecord(1_000, track = listOf(RoutePoint(1_100, b), RoutePoint(1_300, c)))
        val pts = listOf(TrackPoint(1_050, 0.0005, 0.0, null), TrackPoint(1_200, 0.0015, 0.0, null))
        val r = phone.withTurtlePoints(pts, 100)
        assertEquals(listOf(1_050L, 1_100L, 1_200L, 1_300L), r.track.map { it.t })
        assertEquals(2, r.turtlePoints)
        assertEquals(2, r.phonePoints)
        // Fetched again later (one point has been sent to the server since): nothing lost, nothing doubled.
        val again = r.withTurtlePoints(pts.drop(1), 100)
        assertEquals(4, again.track.size)
    }
}
