package com.geovault.tracker

import android.location.Location
import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for QueuedLocation.fromLocation and toLocation round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QueuedLocationTest {

    @Test
    fun fromLocation_preservesTimeLatLon() {
        val loc = Location("gps")
        loc.time = 12345L
        loc.latitude = 40.5
        loc.longitude = -74.2
        val q = QueuedLocation.fromLocation(loc)
        assertEquals(12345L, q.time)
        assertEquals(40.5, q.latitude, 1e-9)
        assertEquals(-74.2, q.longitude, 1e-9)
        assertEquals("gps", q.prov)
    }

    @Test
    fun fromLocation_preservesSpeedBearingAccuracyAltitude() {
        val loc = Location("gps")
        loc.time = 0L
        loc.latitude = 0.0
        loc.longitude = 0.0
        loc.speed = 10.5f
        loc.bearing = 90f
        loc.accuracy = 5f
        loc.altitude = 100.0
        val q = QueuedLocation.fromLocation(loc)
        assertEquals(10.5f, q.speed!!, 1e-6f)
        assertEquals(90f, q.bearing!!, 1e-6f)
        assertEquals(5f, q.accuracy!!, 1e-6f)
        assertEquals(100.0, q.altitude!!, 1e-9)
    }

    @Test
    fun fromLocation_totalDistanceMeters_storedInDist() {
        val loc = Location("gps")
        loc.time = 0L
        loc.latitude = 0.0
        loc.longitude = 0.0
        val q = QueuedLocation.fromLocation(loc, 150.5f)
        assertEquals(150.5f, q.dist!!, 1e-6f)
    }

    @Test
    fun fromLocation_nullDistance_distIsNull() {
        val loc = Location("gps")
        loc.time = 0L
        loc.latitude = 0.0
        loc.longitude = 0.0
        val q = QueuedLocation.fromLocation(loc)
        assertNull(q.dist)
    }

    @Test
    fun fromLocation_noAltitude_altitudeNull() {
        val loc = Location("gps")
        loc.time = 0L
        loc.latitude = 0.0
        loc.longitude = 0.0
        val q = QueuedLocation.fromLocation(loc)
        assertNull(q.altitude)
    }

    @Test
    fun toLocation_roundTrip_preservesTimeLatLonSpeedBearingAccuracy() {
        val loc = Location("gps")
        loc.time = 99999L
        loc.latitude = 39.5
        loc.longitude = -105.0
        loc.speed = 5f
        loc.bearing = 180f
        loc.accuracy = 8f
        loc.altitude = 1600.0
        val q = QueuedLocation.fromLocation(loc, 200f)
        val back = q.toLocation()
        assertEquals(99999L, back.time)
        assertEquals(39.5, back.latitude, 1e-9)
        assertEquals(-105.0, back.longitude, 1e-9)
        assertEquals(5f, back.speed, 1e-6f)
        assertEquals(180f, back.bearing, 1e-6f)
        assertEquals(8f, back.accuracy, 1e-6f)
        assertEquals(1600.0, back.altitude, 1e-9)
        assertEquals("gps", back.provider)
    }
}
