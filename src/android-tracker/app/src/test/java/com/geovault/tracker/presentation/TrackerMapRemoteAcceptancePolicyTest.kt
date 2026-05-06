package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapRemoteAcceptancePolicyTest {

    @Test
    fun merged_keepsProjectedTargetsAndDropsStaleActiveOnlyIds() {
        assertEquals(
            setOf("a", "b"),
            TrackerMapRemoteAcceptancePolicy.mergedAcceptedRemoteTrackerIds(
                streamTargetIds = setOf(" a ", "b"),
                activeStreamedTrackerIds = setOf("c", ""),
            )
        )
    }
}
