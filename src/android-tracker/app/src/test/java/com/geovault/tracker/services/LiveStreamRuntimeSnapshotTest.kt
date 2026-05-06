package com.geovault.tracker.services

import com.geovault.tracker.location.TrackingLifecycleState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamRuntimeSnapshotTest {

    @Test
    fun hasSubscriptionIntent_trueWhileStartingWithTargets() {
        val snapshot = LiveStreamRuntimeSnapshot(
            isRunning = false,
            lifecycleState = TrackingLifecycleState.STARTING,
            activeTrackerIds = setOf("remote"),
        )

        assertTrue(snapshot.hasSubscriptionIntent)
    }

    @Test
    fun hasSubscriptionIntent_falseWhenStopped() {
        val snapshot = LiveStreamRuntimeSnapshot(
            isRunning = false,
            lifecycleState = TrackingLifecycleState.STOPPED,
            activeTrackerIds = setOf("remote"),
        )

        assertFalse(snapshot.hasSubscriptionIntent)
    }
}
