package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapTrailReloadReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReloadCameraFitTest {

    @Test
    fun arm_nonFetchingReasonNeverArms() {
        val fit = PendingReloadCameraFit()

        fit.arm(TrackerMapTrailReloadReason.GenericMapRefresh, generation = 0L)

        assertFalse(
            "A non-fetching reason must never arm the flag, even if a later fetching reason lands.",
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.GenericMapRefresh,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun consumeIfLanded_nonFetchingReasonNeverConsumesEvenIfArmedByFetchingReason() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        val consumed = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.GenericMapRefresh,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 0L,
        )

        assertFalse(consumed)
    }

    @Test
    fun consumeIfLanded_requiresArmedDataPresentAndFetchingReason() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        assertTrue(
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun consumeIfLanded_doesNotConsumeWhenNotArmed() {
        val fit = PendingReloadCameraFit()

        assertFalse(
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun consumeIfLanded_doesNotConsumeWhenNoData() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        assertFalse(
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = false,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun consumeIfLanded_armedLandedWithDataButLockActiveDoesNotFireButStillDisarms() {
        // STREAMING-START LOCK FIGHT: a reload landing while a map lock already owns the camera
        // must not fire an unconditional full-extent fit through this flag. Unlike the old
        // behavior, it also must disarm here -- once a data-bearing landing for the armed reason
        // has occurred, the arm's job is done either way, so a later, unrelated reload can never
        // pick up this stale arm.
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.StreamingStart, generation = 0L)

        val consumed = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.StreamingStart,
            hasData = true,
            anyLockActive = true,
            currentGeneration = 0L,
        )
        assertFalse(consumed)

        val laterUnrelatedConsume = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 0L,
        )
        assertFalse(
            "A lock-blocked landing must disarm so a later, unrelated reload can't consume the stale arm.",
            laterUnrelatedConsume,
        )
    }

    @Test
    fun consumeIfLanded_disarmsAfterSuccessfulConsume() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        val firstConsume = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 0L,
        )
        val secondConsume = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 0L,
        )

        assertTrue(firstConsume)
        assertFalse("Consuming should disarm so a second call without a re-arm is a no-op.", secondConsume)
    }

    @Test
    fun consumeIfLanded_doesNotFireWhenGestureStartedSinceArm_butStillDisarms() {
        // POST-GESTURE SNAP: a fetch armed before the user started panning must not fire a
        // full-extent fit after the gesture bumped the camera generation -- but it still
        // disarms, so it can't be picked up by a later, unrelated reload either.
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        val consumed = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 1L,
        )
        assertFalse("Generation moved on since arm -- a gesture happened, so this must not fire.", consumed)

        val laterUnrelatedConsume = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.RosterChanged,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 1L,
        )
        assertFalse(laterUnrelatedConsume)
    }

    @Test
    fun consumeIfLanded_firesWhenGenerationUnchangedSinceArm() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 3L)

        assertTrue(
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 3L,
            ),
        )
    }

    @Test
    fun disarm_nonFetchingReasonNeverDisarms() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        fit.disarm(TrackerMapTrailReloadReason.GenericMapRefresh)

        assertTrue(
            "A non-fetching reason's disarm call must be a no-op against a fetching-reason arm.",
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun disarm_fetchingReasonDisarms() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        fit.disarm(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        assertFalse(
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }
}
