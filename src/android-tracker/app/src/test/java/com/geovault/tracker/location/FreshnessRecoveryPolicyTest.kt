package com.geovault.tracker.location

import android.location.Location
import com.geovault.tracker.policy.filter.FilterReason
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreshnessRecoveryPolicyTest {
    @Test
    fun staleRecoverableHold_firstFixStartsProbe() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(),
            probeActive = false,
            probeStartedAtMs = 0L,
            promotableProbeFixes = 0,
        )

        assertEquals(FreshnessRecoveryReason.PROBE_STARTED, decision.reason)
    }

    @Test
    fun activeProbe_secondPromotableFixCommitsAnchor() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 1,
        )

        assertEquals(FreshnessRecoveryReason.COMMIT_ANCHOR, decision.reason)
    }

    @Test
    fun activeProbe_firstPromotableFixWaitsForMoreEvidence() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 0,
        )

        assertEquals(FreshnessRecoveryReason.PROBE_WAIT, decision.reason)
    }

    @Test
    fun activeProbe_badAccuracyBlocksCommit() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(accuracyMeters = 90f),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 1,
        )

        assertEquals(FreshnessRecoveryReason.BAD_ACCURACY, decision.reason)
    }

    @Test
    fun activeProbe_nonRecoverableHoldBlocksCommit() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(filterReason = "low-accuracy"),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 1,
        )

        assertEquals(FreshnessRecoveryReason.NOT_RECOVERABLE_HOLD, decision.reason)
    }

    @Test
    fun activeProbe_speedCapExceededCanCommitAnchor() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(filterReason = "speed-cap-exceeded"),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 1,
        )

        assertEquals(FreshnessRecoveryReason.COMMIT_ANCHOR, decision.reason)
    }

    @Test
    fun activeProbe_uncertaintySuppressedAcceptedNotPersistedCanCommitAnchor() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(
                filterReason = "uncertainty-suppressed",
                accepted = true,
                pointPersisted = false,
            ),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 1,
        )

        assertEquals(FreshnessRecoveryReason.COMMIT_ANCHOR, decision.reason)
    }

    @Test
    fun activeProbe_otherAcceptedNotPersistedStillBlocksCommit() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(
                filterReason = "within-cap",
                accepted = true,
                pointPersisted = false,
            ),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 1,
        )

        assertEquals(FreshnessRecoveryReason.ALREADY_ACCEPTED_NOT_PERSISTED, decision.reason)
    }

    @Test
    fun activeProbe_implausibleLargeMoveBlocksAnchorCommit() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(
                candidateLatitude = 45.1,
                candidateLongitude = -122.0,
                candidateTimeMs = 30_000L,
                nowMs = 30_000L,
            ),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 1,
        )

        assertEquals(FreshnessRecoveryReason.IMPLAUSIBLE_MOVE, decision.reason)
    }

    @Test
    fun controller_probeExpired_allowsNewProbeOnNextDueCycle() {
        val controller = FreshnessRecoveryController()
        val baseMs = 1_000_000L
        val windowMs = 30_000L
        val recoveryConfig = PositioningPresets
            .forMotionMode(TrackingMotionMode.WALKING)
            .recoveryConfig(maxLocalPointGapMs = 90_000L)
            .copy(freshnessProbeWindowMs = windowMs)

        fun evalAt(nowMs: Long) = controller.evaluate(
            input(nowMs = nowMs).copy(config = recoveryConfig)
        )

        // First fix starts the probe.
        val start = evalAt(baseMs)
        assertEquals(FreshnessRecoveryReason.PROBE_STARTED, start.reason)

        // Advance beyond the probe window — probe expires.
        val expired = evalAt(baseMs + windowMs + 1L)
        assertEquals(FreshnessRecoveryReason.PROBE_EXPIRED, expired.reason)

        // The controller must have reset so the next overdue cycle starts a fresh probe
        // rather than staying stuck in the expired state forever.
        val restarted = evalAt(baseMs + windowMs + 2L)
        assertEquals(FreshnessRecoveryReason.PROBE_STARTED, restarted.reason)
    }

    @Test
    fun activeProbe_repeatedOutlierBlocksAnchorCommit() {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input(repeatedOutlierSuppressed = true),
            probeActive = true,
            probeStartedAtMs = 10_000L,
            promotableProbeFixes = 1,
        )

        assertEquals(FreshnessRecoveryReason.REPEATED_OUTLIER, decision.reason)
    }

    private fun input(
        filterReason: String = "candidate-unconfirmed",
        accuracyMeters: Float = 12f,
        accepted: Boolean = false,
        pointPersisted: Boolean = false,
        candidateLatitude: Double = 45.0001,
        candidateLongitude: Double = -122.0001,
        candidateTimeMs: Long = 70_000L,
        repeatedOutlierSuppressed: Boolean = false,
        nowMs: Long = candidateTimeMs,
    ): FreshnessRecoveryInput {
        return FreshnessRecoveryInput(
            localRecoveryDue = true,
            accepted = accepted,
            pointPersisted = pointPersisted,
            filterReason = FilterReason.fromWire(filterReason),
            accuracyMeters = accuracyMeters,
            effectiveAccuracyThresholdMeters = 35f,
            candidateLocation = location(latitude = candidateLatitude, longitude = candidateLongitude, timeMs = candidateTimeMs),
            anchor = RecoveryAnchorState.fromLocation(
                trackerId = "tracker-1",
                sessionBoundaryId = 1_000L,
                location = location(latitude = 45.0, longitude = -122.0, timeMs = 10_000L),
                radiusMeters = 50f,
                source = "test",
                motionMode = TrackingMotionMode.WALKING,
            ),
            repeatedOutlierSuppressed = repeatedOutlierSuppressed,
            nowMs = nowMs,
            config = PositioningPresets
                .forMotionMode(TrackingMotionMode.WALKING)
                .recoveryConfig(maxLocalPointGapMs = 90_000L),
        )
    }

    private fun location(latitude: Double, longitude: Double, timeMs: Long): Location {
        return Location("gps").apply {
            this.latitude = latitude
            this.longitude = longitude
            this.time = timeMs
            this.elapsedRealtimeNanos = timeMs * 1_000_000L
            this.accuracy = 10f
        }
    }
}
