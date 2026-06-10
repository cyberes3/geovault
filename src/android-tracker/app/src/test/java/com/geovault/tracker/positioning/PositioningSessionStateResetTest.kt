package com.geovault.tracker.positioning

import android.location.Location
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.services.UploadLivenessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PositioningSessionStateResetTest {

    @Test
    fun resetForStart_clearsSessionScopedMutableFields() {
        val state = seededNonDefaultState()

        state.resetForStart()

        assertEquals(0, state.consecutivePushFailures)
        assertEquals(SyncFailureClass.NONE, state.lastSyncFailureClass)
        assertEquals(0L, state.localTrackPointOrderingCounter.get())
        assertEquals(UploadLivenessState(), state.uploadLivenessState)
        assertNull(state.recoveryAnchorState)
        assertFalse(state.isFastGpsLockWindowActive)
        assertEquals(0, state.lowAccuracyFallbackEmitCountThisSession)
        assertNull(state.lastFilteredLocation)
        assertNull(state.elasticDistanceOverrideMeters)
        assertFalse(state.imuAttentionBoostActive)
        assertEquals(0L, state.stationaryPauseCooldownUntilMs)
        assertEquals(0L, state.lastImuTransitionBoostAtMs)
    }

    @Test
    fun resetForStop_clearsDiagnosticsBoundariesAndUploadPosture() {
        val state = seededNonDefaultState()
        state.sessionVisibleBoundaryId = 99L
        state.sessionBoundaryForBacklogId = 88L

        state.resetForStop()

        assertEquals(0L, state.sessionVisibleBoundaryId)
        assertEquals(0L, state.sessionBoundaryForBacklogId)
        assertEquals(0, state.consecutivePushFailures)
        assertEquals(SyncFailureClass.NONE, state.lastSyncFailureClass)
        assertEquals(UploadLivenessState(), state.uploadLivenessState)
        assertNull(state.recoveryAnchorState)
        assertNull(state.lastFilteredLocation)
        assertNull(state.latestObservedRawLocation)
        assertEquals(0L, state.lastFixDeliveryAtMs)
    }

    private fun seededNonDefaultState(): PositioningSessionState {
        return PositioningSessionState().apply {
            consecutivePushFailures = 3
            lastSyncFailureClass = SyncFailureClass.TRANSIENT
            localTrackPointOrderingCounter.set(42)
            uploadLivenessState = UploadLivenessState(
                lastFailureClass = SyncFailureClass.TRANSIENT,
                consecutiveFailures = 2,
            )
            recoveryAnchorState = com.geovault.tracker.location.RecoveryAnchorState(
                trackerId = "t1",
                sessionBoundaryId = 1L,
                latitude = 1.0,
                longitude = 2.0,
                timestampMs = 1_000L,
                elapsedRealtimeNanos = 1L,
                accuracyMeters = 5f,
                radiusMeters = 10f,
                source = "test",
                motionMode = com.geovault.tracker.services.TrackingMotionMode.WALKING,
            )
            isFastGpsLockWindowActive = true
            lowAccuracyFallbackEmitCountThisSession = 2
            lastFilteredLocation = Location("gps").apply {
                latitude = 1.0
                longitude = 2.0
            }
            elasticDistanceOverrideMeters = 12f
            elasticitySpeedBucket = 2
            gpsRuntimeState = GpsRuntimeState.RUNNING
            lastFixDeliveryAtMs = 5_000L
            imuAttentionBoostActive = true
            stationaryPauseCooldownUntilMs = 9_999_999L
            lastImuTransitionBoostAtMs = 9_999_999L
        }
    }
}
