package com.geovault.tracker.startup

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStartupPolicyTest {
    @Test
    fun evaluate_allowsSupportedBootActionWhenPrerequisitesReady() {
        val decision = BootStartupPolicy.evaluate(
            BootStartupSnapshot(
                action = Intent.ACTION_BOOT_COMPLETED,
                startOnBoot = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
        assertTrue(decision.shouldStartTracking)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun evaluate_blocksUnsupportedAction() {
        val decision = BootStartupPolicy.evaluate(
            BootStartupSnapshot(
                action = Intent.ACTION_TIME_CHANGED,
                startOnBoot = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
        assertFalse(decision.shouldStartTracking)
        assertEquals(listOf(BootStartupBlocker.UnsupportedAction), decision.blockers)
    }

    @Test
    fun evaluate_collectsAllBlockingReasons() {
        val decision = BootStartupPolicy.evaluate(
            BootStartupSnapshot(
                action = Intent.ACTION_BOOT_COMPLETED,
                startOnBoot = false,
                hasRequiredPermissions = false,
                gpsProviderEnabled = false,
                selectedTrackerId = ""
            )
        )

        assertFalse(decision.shouldStartTracking)
        assertEquals(
            listOf(
                BootStartupBlocker.StartOnBootDisabled,
                BootStartupBlocker.MissingTrackingPermissions,
                BootStartupBlocker.GpsProviderDisabled,
                BootStartupBlocker.InvalidSelectedTracker
            ),
            decision.blockers
        )
    }
}
