package com.geovault.tracker.location

import android.location.Location
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

    private fun input(
        filterReason: String = "candidate-unconfirmed",
        accuracyMeters: Float = 12f,
        accepted: Boolean = false,
        pointPersisted: Boolean = false,
    ): FreshnessRecoveryInput {
        return FreshnessRecoveryInput(
            localRecoveryDue = true,
            accepted = accepted,
            pointPersisted = pointPersisted,
            filterReason = filterReason,
            accuracyMeters = accuracyMeters,
            effectiveAccuracyThresholdMeters = 35f,
            candidateLocation = location(latitude = 45.0001, longitude = -122.0001, timeMs = 70_000L),
            anchor = RecoveryAnchorState.fromLocation(
                trackerId = "tracker-1",
                sessionBoundaryId = 1_000L,
                location = location(latitude = 45.0, longitude = -122.0, timeMs = 10_000L),
                radiusMeters = 50f,
                source = "test",
                motionMode = TrackingMotionMode.WALKING,
            ),
            repeatedOutlierSuppressed = false,
            nowMs = 70_000L,
            config = PositioningRecoveryConfig.fromMotionMode(
                motionMode = TrackingMotionMode.WALKING,
                maxLocalPointGapMs = 90_000L,
            ),
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
