package com.geovault.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerGeometryInvalidationPolicyTest {

    @Test
    fun invalidate_incrementsOnlyRequestedTrackers() {
        val next = TrackerGeometryInvalidationPolicy.invalidate(
            trackerIds = listOf(" a ", "b"),
            generationByTrackerId = mapOf("a" to 1L, "c" to 5L),
        )

        assertEquals(2L, next["a"])
        assertEquals(1L, next["b"])
        assertEquals(5L, next["c"])
    }

    @Test
    fun isCurrent_returnsFalseAfterGenerationChanges() {
        val captured = TrackerGeometryInvalidationPolicy.capture(
            trackerIds = listOf("a"),
            generationByTrackerId = mapOf("a" to 1L),
        )
        val next = TrackerGeometryInvalidationPolicy.invalidate(
            trackerIds = listOf("a"),
            generationByTrackerId = mapOf("a" to 1L),
        )

        assertFalse(TrackerGeometryInvalidationPolicy.isCurrent("a", captured, next))
    }

    @Test
    fun isCurrent_allowsUnchangedTrackerFromBulkRequest() {
        val captured = TrackerGeometryInvalidationPolicy.capture(
            trackerIds = listOf("a", "b"),
            generationByTrackerId = mapOf("a" to 1L, "b" to 2L),
        )
        val next = TrackerGeometryInvalidationPolicy.invalidate(
            trackerIds = listOf("a"),
            generationByTrackerId = mapOf("a" to 1L, "b" to 2L),
        )

        assertFalse(TrackerGeometryInvalidationPolicy.isCurrent("a", captured, next))
        assertTrue(TrackerGeometryInvalidationPolicy.isCurrent("b", captured, next))
    }
}
