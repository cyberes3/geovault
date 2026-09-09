package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapTrailReloadReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReloadCameraFitTest {

    @Test
    fun arm_nonFetchingReasonNeverArms() {
        val engine = MapTrailEngine()

        engine.armReloadFit(TrackerMapTrailReloadReason.GenericMapRefresh, generation = 0L)

        assertFalse(
            "A non-fetching reason must never arm the flag, even if a later fetching reason lands.",
            engine.consumeReloadFitIfLanded(
                reason = TrackerMapTrailReloadReason.GenericMapRefresh,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun consumeIfLanded_nonFetchingReasonNeverConsumesEvenIfArmedByFetchingReason() {
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        val consumed = engine.consumeReloadFitIfLanded(
            reason = TrackerMapTrailReloadReason.GenericMapRefresh,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 0L,
        )

        assertFalse(consumed)
    }

    @Test
    fun consumeIfLanded_requiresArmedDataPresentAndFetchingReason() {
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        assertTrue(
            engine.consumeReloadFitIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun consumeIfLanded_doesNotConsumeWhenNotArmed() {
        val engine = MapTrailEngine()

        assertFalse(
            engine.consumeReloadFitIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun consumeIfLanded_doesNotConsumeWhenNoData() {
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        assertFalse(
            engine.consumeReloadFitIfLanded(
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
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.StreamingStart, generation = 0L)

        val consumed = engine.consumeReloadFitIfLanded(
            reason = TrackerMapTrailReloadReason.StreamingStart,
            hasData = true,
            anyLockActive = true,
            currentGeneration = 0L,
        )
        assertFalse(consumed)

        val laterUnrelatedConsume = engine.consumeReloadFitIfLanded(
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
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        val firstConsume = engine.consumeReloadFitIfLanded(
            reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 0L,
        )
        val secondConsume = engine.consumeReloadFitIfLanded(
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
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        val consumed = engine.consumeReloadFitIfLanded(
            reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 1L,
        )
        assertFalse("Generation moved on since arm -- a gesture happened, so this must not fire.", consumed)

        val laterUnrelatedConsume = engine.consumeReloadFitIfLanded(
            reason = TrackerMapTrailReloadReason.RosterChanged,
            hasData = true,
            anyLockActive = false,
            currentGeneration = 1L,
        )
        assertFalse(laterUnrelatedConsume)
    }

    @Test
    fun consumeIfLanded_firesWhenGenerationUnchangedSinceArm() {
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 3L)

        assertTrue(
            engine.consumeReloadFitIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 3L,
            ),
        )
    }

    @Test
    fun disarm_nonFetchingReasonNeverDisarms() {
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        engine.disarmReloadFit(TrackerMapTrailReloadReason.GenericMapRefresh)

        assertTrue(
            "A non-fetching reason's disarm call must be a no-op against a fetching-reason arm.",
            engine.consumeReloadFitIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }

    @Test
    fun disarm_fetchingReasonDisarms() {
        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)

        engine.disarmReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        assertFalse(
            engine.consumeReloadFitIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            ),
        )
    }
}
