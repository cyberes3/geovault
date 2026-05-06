package com.geovault.tracker.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTargetPolicyTest {

    @Test
    fun remoteSubscriptionTargets_excludesSelectedLocalAndExplicitIds() {
        val targets = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = setOf(" selected ", "local", "remote", "excluded", " "),
                selectedTrackerId = "selected",
                locallyRecordedTrackerIds = setOf("local"),
                excludedTrackerIds = setOf("excluded"),
            )
        )

        assertEquals(setOf("remote"), targets)
    }
}
