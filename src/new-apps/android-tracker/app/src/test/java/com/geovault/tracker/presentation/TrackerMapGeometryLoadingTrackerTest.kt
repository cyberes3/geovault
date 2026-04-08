package com.geovault.tracker.presentation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapGeometryLoadingTrackerTest {

    @Test
    fun track_togglesLoadingAroundSingleRequest() = runBlocking {
        val transitions = mutableListOf<Boolean>()
        val tracker = TrackerMapGeometryLoadingTracker(onLoadingChanged = transitions::add)

        tracker.track { }

        assertEquals(listOf(true, false), transitions)
    }

    @Test
    fun track_nestedRequestsEmitOneEnableAndOneDisable() = runBlocking {
        val transitions = mutableListOf<Boolean>()
        val tracker = TrackerMapGeometryLoadingTracker(onLoadingChanged = transitions::add)

        tracker.track {
            tracker.track { }
        }

        assertEquals(listOf(true, false), transitions)
    }
}
