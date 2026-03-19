package com.geovault.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun shouldStartTrackingOnBoot_requiresAllPrerequisites() {
        assertFalse(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = false,
                hasRequiredPermissions = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
        assertFalse(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = true,
                hasRequiredPermissions = false,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
        assertFalse(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = true,
                hasRequiredPermissions = true,
                selectedTrackerId = ""
            )
        )
        assertTrue(
            BootReceiver.shouldStartTrackingOnBoot(
                startOnBoot = true,
                hasRequiredPermissions = true,
                selectedTrackerId = "00000000-0000-0000-0000-000000000001"
            )
        )
    }
}
