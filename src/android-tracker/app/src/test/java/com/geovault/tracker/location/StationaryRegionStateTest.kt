package com.geovault.tracker.location

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StationaryRegionStateTest {
    @Test
    fun enterAndProbeTrackExplicitRegionState() {
        val anchor = RecoveryAnchorState.fromLocation(
            trackerId = "tracker-1",
            sessionBoundaryId = 1_000L,
            location = Location("gps").apply {
                latitude = 45.0
                longitude = -122.0
                time = 1_000L
            },
            radiusMeters = 50f,
            source = "test",
            motionMode = TrackingMotionMode.WALKING,
        )

        val state = StationaryRegionState()
            .enter(anchor = anchor, nowMs = 2_000L)
            .startProbe(nowMs = 5_000L)
            .recordPoorAccuracyFix()

        assertTrue(state.hasRegion)
        assertTrue(state.probeActive)
        assertEquals(5_000L, state.probeStartedAtMs)
        assertEquals(1, state.poorAccuracyFixes)
    }

    @Test
    fun markFreshnessPointPersistedClearsProbeButKeepsRegion() {
        val anchor = RecoveryAnchorState(
            trackerId = "tracker-1",
            sessionBoundaryId = 1_000L,
            latitude = 1.0,
            longitude = 2.0,
            timestampMs = 1_000L,
            elapsedRealtimeNanos = 1_000_000L,
            accuracyMeters = null,
            radiusMeters = 50f,
            source = "test",
            motionMode = TrackingMotionMode.WALKING,
        )

        val state = StationaryRegionState()
            .enter(anchor = anchor, nowMs = 2_000L)
            .startProbe(nowMs = 5_000L)
            .markFreshnessPointPersisted(nowMs = 10_000L)

        assertTrue(state.hasRegion)
        assertFalse(state.probeActive)
        assertEquals(10_000L, state.lastFreshnessPointAtMs)
    }

    @Test
    fun poorAccuracyStationaryFixesDoNotMoveRegionAnchor() {
        val anchor = RecoveryAnchorState(
            trackerId = "tracker-1",
            sessionBoundaryId = 1_000L,
            latitude = 45.0,
            longitude = -122.0,
            timestampMs = 1_000L,
            elapsedRealtimeNanos = 1_000_000L,
            accuracyMeters = 8f,
            radiusMeters = 50f,
            source = "test",
            motionMode = TrackingMotionMode.WALKING,
        )

        val state = StationaryRegionState()
            .enter(anchor = anchor, nowMs = 2_000L)
            .recordPoorAccuracyFix()
            .recordPoorAccuracyFix()

        assertEquals(anchor, state.anchor)
        assertEquals(2, state.poorAccuracyFixes)
    }

    @Test
    fun stationaryRegionStoreRestoresMatchingContextAndFreshnessTime() {
        val store = StationaryRegionStore(ApplicationProvider.getApplicationContext())
        store.clear()
        val anchor = RecoveryAnchorState(
            trackerId = "tracker-1",
            sessionBoundaryId = 1_000L,
            latitude = 1.0,
            longitude = 2.0,
            timestampMs = 1_000L,
            elapsedRealtimeNanos = 1_000_000L,
            accuracyMeters = 8f,
            radiusMeters = 50f,
            source = "stationary_region",
            motionMode = TrackingMotionMode.WALKING,
        )
        val state = StationaryRegionState()
            .enter(anchor = anchor, nowMs = 2_000L)
            .markFreshnessPointPersisted(nowMs = 10_000L)

        store.save(state)
        val restored = store.load(trackerId = "tracker-1", sessionBoundaryId = 1_000L)

        assertEquals(10_000L, restored?.lastFreshnessPointAtMs)
        assertEquals(2_000L, restored?.enteredAtMs)
        assertEquals(null, store.load(trackerId = "tracker-2", sessionBoundaryId = 1_000L))
        assertEquals(null, store.load(trackerId = "tracker-1", sessionBoundaryId = 2_000L))
    }

    @Test
    fun recoveryAnchorStoreRejectsDifferentTrackerOrSessionContext() {
        val store = RecoveryAnchorStore(ApplicationProvider.getApplicationContext())
        store.clear()
        val anchor = RecoveryAnchorState(
            trackerId = "tracker-1",
            sessionBoundaryId = 1_000L,
            latitude = 1.0,
            longitude = 2.0,
            timestampMs = 1_000L,
            elapsedRealtimeNanos = 1_000_000L,
            accuracyMeters = 8f,
            radiusMeters = 50f,
            source = "test",
            motionMode = TrackingMotionMode.WALKING,
        )

        store.save(anchor)

        assertEquals(anchor, store.load(trackerId = "tracker-1", sessionBoundaryId = 1_000L))
        assertEquals(null, store.load(trackerId = "tracker-2", sessionBoundaryId = 1_000L))
        assertEquals(null, store.load(trackerId = "tracker-1", sessionBoundaryId = 2_000L))
    }
}
