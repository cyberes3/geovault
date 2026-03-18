package com.geovault.tracker.fragments.map

import com.geovault.tracker.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MapAllTrackersFlowRestoreTest {
    @Test
    fun cameraPolicy_singleMode_enablesFollowLockWhenRequested() {
        val useCase = ApplyCameraPolicyUseCase()
        val command = useCase.forMode(
            mode = MapScreenMode.Single,
            currentSelection = null,
            enableFollowLock = true
        )

        assertEquals(true, command.followLockEnabled)
        assertEquals(false, command.fitBounds)
        assertNull(command.targetTrackerId)
    }

    @Test
    fun cameraPolicy_groupMode_disablesFollowLock_andFitsBounds() {
        val useCase = ApplyCameraPolicyUseCase()
        val command = useCase.forMode(
            mode = MapScreenMode.GroupMode(Group(id = "g1", name = "Group")),
            currentSelection = null,
            enableFollowLock = true
        )

        assertFalse(command.followLockEnabled)
        assertEquals(true, command.fitBounds)
    }
}

