package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapTrailMaterializationPolicyTest {

    // Use realistic ms-scale wall-clock timestamps so WireTimestampNormalizer treats them as
    // already-in-milliseconds (>= 1e12) rather than seconds (which would multiply by 1000).
    private val sessionAStartMs = 1_700_000_000_000L
    private val sessionBStartMs = 1_700_000_010_000L

    private fun coord(lon: Double, lat: Double, tsMs: Long? = null): List<Double> {
        return if (tsMs != null) listOf(lon, lat, tsMs.toDouble()) else listOf(lon, lat)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        val out = TrackerMapTrailMaterializationPolicy.materialize(
            trackerId = "T",
            coordinates = emptyList(),
            trailPointLimit = 100,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun blankTrackerIdReturnsEmpty() {
        val out = TrackerMapTrailMaterializationPolicy.materialize(
            trackerId = "   ",
            coordinates = listOf(coord(1.0, 2.0, sessionAStartMs)),
            trailPointLimit = 100,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun pointParamsProvided_eachPointGetsItsStartTimestamp() {
        // Regression test: if pointParams isn't threaded through, the recent-data-window
        // session filter cannot attribute previous-session points and they vanish.
        val coordinates = listOf(
            coord(0.0, 0.0, sessionAStartMs + 100),
            coord(1.0, 1.0, sessionAStartMs + 200),
            coord(2.0, 2.0, sessionBStartMs + 100),
        )
        val pointParams = listOf<Map<String, Any?>>(
            mapOf("starttimestamp" to sessionAStartMs),
            mapOf("starttimestamp" to sessionAStartMs),
            mapOf("starttimestamp" to sessionBStartMs),
        )
        val out = TrackerMapTrailMaterializationPolicy.materialize(
            trackerId = "T",
            coordinates = coordinates,
            pointParams = pointParams,
            trailPointLimit = 100,
        )
        assertEquals(3, out.size)
        assertEquals(sessionAStartMs, out[0].startTimestampMs)
        assertEquals(sessionAStartMs, out[1].startTimestampMs)
        assertEquals(sessionBStartMs, out[2].startTimestampMs)
    }

    @Test
    fun pointParamsMissing_startTimestampIsNullForEveryPoint() {
        // Documents the broken baseline that the regression test above guards against.
        val coordinates = listOf(
            coord(0.0, 0.0, sessionAStartMs + 100),
            coord(1.0, 1.0, sessionAStartMs + 200),
        )
        val out = TrackerMapTrailMaterializationPolicy.materialize(
            trackerId = "T",
            coordinates = coordinates,
            pointParams = null,
            trailPointLimit = 100,
        )
        assertEquals(2, out.size)
        assertNull(out[0].startTimestampMs)
        assertNull(out[1].startTimestampMs)
    }

    @Test
    fun lastPointReceivesAccuracyFromLastPointParams() {
        val coordinates = listOf(
            coord(0.0, 0.0, sessionAStartMs + 100),
            coord(1.0, 1.0, sessionAStartMs + 200),
        )
        val pointParams = listOf<Map<String, Any?>>(
            mapOf("acc" to 50.0),
            mapOf("acc" to 7.5),
        )
        val out = TrackerMapTrailMaterializationPolicy.materialize(
            trackerId = "T",
            coordinates = coordinates,
            pointParams = pointParams,
            trailPointLimit = 100,
        )
        assertEquals(2, out.size)
        assertNull("non-last point keeps null accuracy", out[0].accuracy)
        assertEquals(7.5f, out[1].accuracy)
    }

    @Test
    fun overTrailPointLimit_decimatesViaSessionAwarePolicy() {
        // Force a 2-session input above the cap and verify both session anchors survive.
        val coordinates = mutableListOf<List<Double>>()
        val pointParams = mutableListOf<Map<String, Any?>>()
        repeat(20) { idx ->
            coordinates.add(coord(0.0, idx.toDouble(), sessionAStartMs + idx))
            pointParams.add(mapOf("starttimestamp" to sessionAStartMs))
        }
        repeat(20) { idx ->
            coordinates.add(coord(1.0, idx.toDouble(), sessionBStartMs + idx))
            pointParams.add(mapOf("starttimestamp" to sessionBStartMs))
        }
        val out = TrackerMapTrailMaterializationPolicy.materialize(
            trackerId = "T",
            coordinates = coordinates,
            pointParams = pointParams,
            trailPointLimit = 16,
        )
        assertTrue("size <= cap (got ${out.size})", out.size <= 16)
        assertNotNull(
            "session A anchor preserved",
            out.firstOrNull { it.startTimestampMs == sessionAStartMs },
        )
        assertNotNull(
            "session B anchor preserved",
            out.lastOrNull { it.startTimestampMs == sessionBStartMs },
        )
    }
}
