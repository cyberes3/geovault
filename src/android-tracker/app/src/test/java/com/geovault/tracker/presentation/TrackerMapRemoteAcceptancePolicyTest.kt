package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapRemoteAcceptancePolicyTest {

    @Test
    fun merged_unionsTrimsAndDeduplicates() {
        assertEquals(
            setOf("a", "b", "c"),
            TrackerMapRemoteAcceptancePolicy.mergedAcceptedRemoteTrackerIds(
                streamTargetIds = setOf(" a ", "b"),
                activeStreamedTrackerIds = setOf("c", ""),
            )
        )
    }
}
