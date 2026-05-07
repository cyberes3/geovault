package com.geovault.tracker.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTargetPolicyTest {

    @Test
    fun remoteSubscriptionTargets_excludesLocallyRecordedAndBlanks() {
        val targets = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = setOf(" local ", "remote", " "),
                locallyRecordedTrackerIds = setOf("local"),
            )
        )

        assertEquals(setOf("remote"), targets)
    }

    @Test
    fun remoteSubscriptionTargets_keepsAllRequestedWhenNoLocalRecorder() {
        val targets = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = setOf("a", "b"),
            )
        )

        assertEquals(setOf("a", "b"), targets)
    }
}
