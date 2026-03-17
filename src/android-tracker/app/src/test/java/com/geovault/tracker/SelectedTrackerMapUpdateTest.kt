package com.geovault.tracker

import com.geovault.tracker.pipeline.TrackPointBus
import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

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
    fun trackPointBus_emitsPublishedEventPayload() = runBlocking {
        val expected = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "tracker-1",
            lon = 56.78,
            lat = 12.34,
            timestampMs = 123456789L,
            accuracyMeters = 8.5f,
            propsJson = "{\"acc\":8.5}"
        )

        TrackPointBus.publish(expected)

        val actual = withTimeout(1000L) { TrackPointBus.events.first { it.trackId == "tracker-1" } }
        assertEquals(expected, actual)
    }
}
