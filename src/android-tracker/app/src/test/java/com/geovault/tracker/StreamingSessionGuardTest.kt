package com.geovault.tracker

import com.geovault.tracker.location.TrackingLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingSessionGuardTest {

    @Test
    fun assess_returnsReuse_whenSocketRunningAndRecentActivity() {
        var nowMs = 1_000L
        val guard = StreamingSessionGuard(staleAfterMs = 45_000L, elapsedRealtimeMs = { nowMs })
        guard.markConnected()
        nowMs += 1_500L

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.REUSE, assessment.decision)
        assertTrue((assessment.activityAgeMs ?: Long.MAX_VALUE) in 0L..45_000L)
    }

    @Test
    fun assess_returnsStaleActivity_whenActivityTooOld() {
        var nowMs = 10_000L
        val guard = StreamingSessionGuard(staleAfterMs = 2_000L, elapsedRealtimeMs = { nowMs })
        guard.markConnected()
        nowMs += 5_000L

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.STALE_ACTIVITY, assessment.decision)
        assertTrue((assessment.activityAgeMs ?: 0L) > 2_000L)
    }

    @Test
    fun assess_returnsHotUpdate_whenTargetSetDiffersButSocketHealthy() {
        var nowMs = 1_000L
        val guard = StreamingSessionGuard(staleAfterMs = 45_000L, elapsedRealtimeMs = { nowMs })
        guard.markConnected()
        nowMs += 1_500L

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a", "b"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.HOT_UPDATE, assessment.decision)
    }

    @Test
    fun assess_returnsNoSocket_whenTargetSetDiffersAndNoSocket() {
        val guard = StreamingSessionGuard(staleAfterMs = 45_000L, elapsedRealtimeMs = { 1_000L })

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a", "b"),
            currentTrackerIds = setOf("a"),
            hasSocket = false,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.NO_SOCKET, assessment.decision)
    }

    @Test
    fun assess_returnsStaleActivity_whenTargetSetDiffersButActivityTooOld() {
        var nowMs = 10_000L
        val guard = StreamingSessionGuard(staleAfterMs = 2_000L, elapsedRealtimeMs = { nowMs })
        guard.markConnected()
        nowMs += 5_000L

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a", "b"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.STALE_ACTIVITY, assessment.decision)
    }
}
