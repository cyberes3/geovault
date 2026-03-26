package com.geovault.tracker

import com.geovault.tracker.location.TrackingLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingSessionGuardTest {
    @Test
    fun assess_reuseWhenSessionIsRunningAndRecent() {
        var nowMs = 1_000L
        val guard = StreamingSessionGuard(
            staleAfterMs = 5_000L,
            elapsedRealtimeMs = { nowMs }
        )

        guard.markConnected()
        nowMs += 100L

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.REUSE, assessment.decision)
    }

    @Test
    fun assess_staleActivityForcesReconnect() {
        var nowMs = 1_000L
        val guard = StreamingSessionGuard(
            staleAfterMs = 500L,
            elapsedRealtimeMs = { nowMs }
        )

        guard.markConnected()
        nowMs += 1_000L

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.STALE_ACTIVITY, assessment.decision)
    }

    @Test
    fun assess_afterDisconnectNeverReusesSession() {
        var nowMs = 1_000L
        val guard = StreamingSessionGuard(
            staleAfterMs = 5_000L,
            elapsedRealtimeMs = { nowMs }
        )

        guard.markConnected()
        guard.markDisconnected()
        nowMs += 100L

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.NO_ACTIVITY, assessment.decision)
    }
}
