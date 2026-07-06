package com.geovault.tracker

import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.streaming.StreamingConfig
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

    @Test
    fun assess_returnsReuse_whenPongReceivedLongAfterLastTrackerPoint() {
        // Regression test: staleness must be driven by the app-level pong, never by how long ago
        // the watched tracker itself last reported a point. A quiet-but-healthy tracker (sparse
        // tracking, or simply stationary) can go well past `staleAfterMs` between real points
        // without the connection being stale.
        var nowMs = 0L
        val guard = StreamingSessionGuard(staleAfterMs = 45_000L, elapsedRealtimeMs = { nowMs })
        guard.markConnected()

        nowMs += 40_000L
        guard.markLivenessReceived()

        nowMs += 40_000L // 80s since connect, well past staleAfterMs, but only 40s since the pong

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.REUSE, assessment.decision)
    }

    @Test
    fun assess_returnsReuse_whenTrackerPointRefreshesLivenessWithNoPongYet() {
        // Defense-in-depth: an incoming track_updated point is a secondary liveness signal
        // alongside the pong, guarding against the pong path alone ever silently regressing
        // server-side.
        var nowMs = 0L
        val guard = StreamingSessionGuard(staleAfterMs = 45_000L, elapsedRealtimeMs = { nowMs })
        guard.markConnected()

        nowMs += 40_000L
        guard.markLivenessReceived()

        nowMs += 40_000L // 80s since connect, well past staleAfterMs, but only 40s since the point

        val assessment = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )

        assertEquals(StreamingSessionReuseDecision.REUSE, assessment.decision)
    }

    @Test
    fun createDefault_usesStreamingConfigSessionStaleAfterMs() {
        var nowMs = 1_000L
        val guard = StreamingSessionGuard(elapsedRealtimeMs = { nowMs })
        guard.markConnected()

        nowMs += StreamingConfig.sessionStaleAfterMs - 1L
        val stillFresh = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )
        assertEquals(StreamingSessionReuseDecision.REUSE, stillFresh.decision)

        nowMs += 2L
        val nowStale = guard.assess(
            requestedTrackerIds = setOf("a"),
            currentTrackerIds = setOf("a"),
            hasSocket = true,
            lifecycleState = TrackingLifecycleState.RUNNING
        )
        assertEquals(StreamingSessionReuseDecision.STALE_ACTIVITY, nowStale.decision)
    }
}
