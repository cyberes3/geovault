package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapTrailReloadReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReloadCameraFitTest {

    @Test
    fun arm_nonFetchingReasonNeverArms() {
        val fit = PendingReloadCameraFit()

        fit.arm(TrackerMapTrailReloadReason.GenericMapRefresh)

        assertFalse(
            "A non-fetching reason must never arm the flag, even if a later fetching reason lands.",
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.GenericMapRefresh,
                hasData = true,
                anyLockActive = false,
            ),
        )
    }

    @Test
    fun consumeIfLanded_nonFetchingReasonNeverConsumesEvenIfArmedByFetchingReason() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        val consumed = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.GenericMapRefresh,
            hasData = true,
            anyLockActive = false,
        )

        assertFalse(consumed)
    }

    @Test
    fun consumeIfLanded_requiresArmedDataPresentAndFetchingReason() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        assertTrue(
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
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
            ),
        )
    }

    @Test
    fun consumeIfLanded_doesNotConsumeWhenNoData() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        assertFalse(
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = false,
                anyLockActive = false,
            ),
        )
    }

    @Test
    fun consumeIfLanded_armedLandedWithDataButLockActiveDoesNotConsumeOrFire() {
        // STREAMING-START LOCK FIGHT: a reload landing while a map lock already owns the camera
        // must not fire an unconditional full-extent fit through this flag -- and per
        // [consumeIfLanded]'s contract it also must not consume/disarm in that case, so a
        // subsequent landing after the lock releases can still fire.
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.StreamingStart)

        val consumed = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.StreamingStart,
            hasData = true,
            anyLockActive = true,
        )

        assertFalse(consumed)
    }

    @Test
    fun consumeIfLanded_disarmsAfterSuccessfulConsume() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        val firstConsume = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            hasData = true,
            anyLockActive = false,
        )
        val secondConsume = fit.consumeIfLanded(
            reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
            hasData = true,
            anyLockActive = false,
        )

        assertTrue(firstConsume)
        assertFalse("Consuming should disarm so a second call without a re-arm is a no-op.", secondConsume)
    }

    @Test
    fun disarm_nonFetchingReasonNeverDisarms() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        fit.disarm(TrackerMapTrailReloadReason.GenericMapRefresh)

        assertTrue(
            "A non-fetching reason's disarm call must be a no-op against a fetching-reason arm.",
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
            ),
        )
    }

    @Test
    fun disarm_fetchingReasonDisarms() {
        val fit = PendingReloadCameraFit()
        fit.arm(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        fit.disarm(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        assertFalse(
            fit.consumeIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
            ),
        )
    }
}
