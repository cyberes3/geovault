package com.geovault.tracker

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackingServiceLocationUpdateIntentTest {
    @Test
    fun buildLocationUpdateIntent_roundTripsLocationCopies() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 5f
            time = 1234L
        }

        val intent = TrackingService.buildLocationUpdateIntent(context, listOf(original))
        original.latitude = 99.0

        val extracted = TrackingService.extractLocationUpdateIntentLocations(intent)

        assertEquals(TrackingService.ACTION_LOCATION_UPDATE, intent.action)
        assertEquals(1, extracted.size)
        assertEquals(10.0, extracted.single().latitude, 0.0)
        assertEquals(20.0, extracted.single().longitude, 0.0)
        assertEquals(5f, extracted.single().accuracy, 0.0f)
        assertEquals(1234L, extracted.single().time)
    }

    @Test
    fun extractLocationUpdateIntentLocations_ignoresOtherActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = TrackingService.buildLocationUpdateIntent(
            context = context,
            locations = listOf(Location("gps"))
        ).apply {
            action = TrackingService.ACTION_RESHOW_FOREGROUND
        }

        assertTrue(TrackingService.extractLocationUpdateIntentLocations(intent).isEmpty())
    }
}
