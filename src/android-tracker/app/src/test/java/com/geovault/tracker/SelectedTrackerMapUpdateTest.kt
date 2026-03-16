package com.geovault.tracker

import android.content.Intent
import com.geovault.tracker.fragments.map.MapBroadcastHandlers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression tests for selected-tracker map update behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectedTrackerMapUpdateTest {

    @Test
    fun trackerRepository_clearGeometryCache_existsAndCallable() {
        TrackerRepository.clearGeometryCache()
    }

    @Test
    fun trackerRepository_clearSelectedTrackerCaches_existsAndCallable() {
        TrackerRepository.clearSelectedTrackerCaches()
    }

    @Test
    fun trackerRepository_clearListCaches_existsAndCallable() {
        TrackerRepository.clearListCaches()
    }

    @Test
    fun liveTrackBroadcast_constantsMatchContract() {
        assertFalse(LiveTrackStreamingService.BROADCAST_TRACK_POINT.isBlank())
        assertFalse(LiveTrackStreamingService.EXTRA_TRACK_ID.isBlank())
        assertFalse(LiveTrackStreamingService.EXTRA_POINT_LAT.isBlank())
        assertFalse(LiveTrackStreamingService.EXTRA_POINT_LON.isBlank())
        assertFalse(LiveTrackStreamingService.EXTRA_POINT_TS_MS.isBlank())
    }

    @Test
    fun liveTrackPointReceiver_parsesBroadcastPayload() {
        var trackId: String? = null
        var lat = 0.0
        var lon = 0.0
        var tsMs = 0L
        var accuracy: Float? = null
        val receiver = MapBroadcastHandlers.createLiveTrackPointReceiver { id, aLat, aLon, aTs, aAccuracy ->
            trackId = id
            lat = aLat
            lon = aLon
            tsMs = aTs
            accuracy = aAccuracy
        }
        val intent = Intent(LiveTrackStreamingService.BROADCAST_TRACK_POINT).apply {
            putExtra(LiveTrackStreamingService.EXTRA_TRACK_ID, "tracker-1")
            putExtra(LiveTrackStreamingService.EXTRA_POINT_LAT, 12.34)
            putExtra(LiveTrackStreamingService.EXTRA_POINT_LON, 56.78)
            putExtra(LiveTrackStreamingService.EXTRA_POINT_TS_MS, 123456789L)
            putExtra(LiveTrackStreamingService.EXTRA_ACCURACY_METERS, 8.5f)
        }

        receiver.onReceive(RuntimeEnvironment.getApplication(), intent)

        assertEquals("tracker-1", trackId)
        assertEquals(12.34, lat, 0.0)
        assertEquals(56.78, lon, 0.0)
        assertEquals(123456789L, tsMs)
        assertEquals(8.5f, accuracy)
    }
}
