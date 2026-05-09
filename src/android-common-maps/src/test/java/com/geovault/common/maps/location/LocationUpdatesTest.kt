package com.geovault.common.maps.location

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocationUpdatesTest {
    @Test
    fun validLocationOrNull_preservesProviderLocationAndAccuracy() {
        val location = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 37f
        }

        val result = LocationUpdates.validLocationOrNull(location)

        assertSame(location, result)
        assertEquals(37f, result!!.accuracy, 0f)
    }

    @Test
    fun validLocationOrNull_rejectsInvalidCoordinates() {
        val location = Location("gps").apply {
            latitude = 95.0
            longitude = 20.0
            accuracy = 37f
        }

        assertNull(LocationUpdates.validLocationOrNull(location))
    }
}
