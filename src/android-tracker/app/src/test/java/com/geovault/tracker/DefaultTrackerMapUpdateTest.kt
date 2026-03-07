package com.geovault.tracker

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for "default tracker map update" behavior.
 *
 * CONTRACT (see .cursor/rules/android-tracker-default-tracker-map.mdc):
 * - Map must show latest default-tracker data when switching to Map tab (refetch on resume, clear geometry cache).
 * - Live updates: LiveTrackStreamingService runs only for a non-default displayed tracker (default track data is local).
 *   LOCATION_UPDATE is applied when the map is showing the default tracker.
 * - When no default tracker: map is cleared on resume. When selected tracker is deleted: clear repository cache.
 * - All in-app broadcasts use setPackage(packageName).
 *
 * These tests ensure the repository cache-clearing API and broadcast contract constants remain in place
 * so that MapFragment, TrackingService, and LiveTrackStreamingService stay in sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DefaultTrackerMapUpdateTest {

    @Test
    fun trackerRepository_clearGeometryCache_existsAndCallable() {
        TrackerRepository.clearGeometryCache()
    }

    @Test
    fun trackerRepository_clearCurrentTrackerCache_existsAndCallable() {
        TrackerRepository.clearCurrentTrackerCache()
    }

    @Test
    fun liveTrackBroadcast_constantsMatchContract() {
        assertFalse(LiveTrackStreamingService.BROADCAST_TRACK_POINT.isBlank())
        assertFalse(LiveTrackStreamingService.EXTRA_TRACK_ID.isBlank())
        assertFalse(LiveTrackStreamingService.EXTRA_POINT_LAT.isBlank())
        assertFalse(LiveTrackStreamingService.EXTRA_POINT_LON.isBlank())
        assertFalse(LiveTrackStreamingService.EXTRA_POINT_TS_MS.isBlank())
    }
}
