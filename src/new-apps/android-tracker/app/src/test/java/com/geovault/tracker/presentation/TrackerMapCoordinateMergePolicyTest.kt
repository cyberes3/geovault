package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapCoordinateMergePolicyTest {

    @Test
    fun mergedCoordinates_prefersLatestBaseAndMergesNewerPoints() {
        val geometry = listOf(
            listOf(1.0, 2.0, 1_700_000_000_000.0),
            listOf(1.1, 2.1, 1_700_000_000_500.0)
        )
        val response = listOf(
            listOf(1.0, 2.0, 1_700_000_000_000.0),
            listOf(1.2, 2.2, 1_700_000_001_000.0)
        )
        val merged = TrackerMapCoordinateMergePolicy.mergedCoordinates(geometry, response)
        assertEquals(2, merged.size)
        assertEquals(1.2, merged.last()[0], 1e-9)
    }

    @Test
    fun mergedCoordinates_dropsOutOfOrderOlderPointFromSecondaryList() {
        val geometry = listOf(
            listOf(1.0, 2.0, 1_700_000_001_000.0)
        )
        val response = listOf(
            listOf(0.9, 1.9, 1_700_000_000_000.0)
        )
        val merged = TrackerMapCoordinateMergePolicy.mergedCoordinates(geometry, response)
        assertEquals(1, merged.size)
        assertEquals(1.0, merged.first()[0], 1e-9)
    }

    @Test
    fun mergedCoordinates_normalizesSecondTimestampsToMilliseconds() {
        val geometry = listOf(listOf(1.0, 2.0, 1_700_000_000.0))
        val response = emptyList<List<Double>>()
        val merged = TrackerMapCoordinateMergePolicy.mergedCoordinates(geometry, response)
        assertTrue(merged.first()[2] > 1_700_000_000_000.0 - 1)
    }
}
