package org.hopeturtles.wrangler.ui

import org.hopeturtles.wrangler.ble.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTest {
    @Test fun distance() {
        // One degree of latitude ≈ 111.19 km on the 6371 km sphere.
        assertEquals(111_195L, Geo.distanceM(LatLon(0.0, 0.0), LatLon(1.0, 0.0)))
        assertEquals(0L, Geo.distanceM(LatLon(50.85, -1.41), LatLon(50.85, -1.41)))
        assertNull(Geo.distanceM(null, LatLon(0.0, 0.0)))
    }
}
