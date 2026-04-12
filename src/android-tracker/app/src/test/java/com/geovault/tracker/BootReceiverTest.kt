package com.geovault.tracker

import com.geovault.tracker.settings.TrackerSettingsLoadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun shouldStartTrackingOnBoot_requiresAllPrerequisites() {
        assertFalse(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = false,
                wasTrackingBeforeExit = false,
                userUnlocked = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
        assertFalse(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = true,
                wasTrackingBeforeExit = false,
                userUnlocked = true,
                hasRequiredPermissions = false,
                gpsProviderEnabled = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
        assertFalse(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = true,
                wasTrackingBeforeExit = false,
                userUnlocked = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = false,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
        assertFalse(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = true,
                wasTrackingBeforeExit = false,
                userUnlocked = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = ""
            )
        )
        assertTrue(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = true,
                wasTrackingBeforeExit = false,
                userUnlocked = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
    }

    @Test
    fun shouldStartTrackingOnBoot_allowsRecoveryFromPreviousActiveSession() {
        assertTrue(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = false,
                wasTrackingBeforeExit = true,
                userUnlocked = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
    }

    @Test
    fun shouldStartTrackingOnBoot_requiresUserUnlocked() {
        assertFalse(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = true,
                wasTrackingBeforeExit = true,
                userUnlocked = false,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
    }

    @Test
    fun shouldProcessSettingsState_onlyWhenReady() {
        assertFalse(BootReceiver.shouldProcessSettingsState(TrackerSettingsLoadState.Loading))
        assertFalse(BootReceiver.shouldProcessSettingsState(TrackerSettingsLoadState.Error))
        assertTrue(BootReceiver.shouldProcessSettingsState(TrackerSettingsLoadState.Ready))
    }
}
