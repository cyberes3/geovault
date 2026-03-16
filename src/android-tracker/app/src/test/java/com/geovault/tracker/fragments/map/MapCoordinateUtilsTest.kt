package com.geovault.tracker.fragments.map

import com.geovault.tracker.TrackUpdateHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCoordinateUtilsTest {
    @Test
    fun normalizeTimestampToMs_convertsSecondsAndKeepsMilliseconds() {
        assertEquals(1_710_000_000_000L, MapCoordinateUtils.normalizeTimestampToMs(1_710_000_000L))
        assertEquals(1_710_000_000_123L, MapCoordinateUtils.normalizeTimestampToMs(1_710_000_000_123L))
    }

    @Test
    fun normalizeTimestampToMs_handlesBoundaryAndNonPositiveValues() {
        assertEquals(0L, MapCoordinateUtils.normalizeTimestampToMs(0L))
        assertEquals(-10L, MapCoordinateUtils.normalizeTimestampToMs(-10L))
        assertEquals(999_999_999_999_000L, MapCoordinateUtils.normalizeTimestampToMs(999_999_999_999L))
        assertEquals(1_000_000_000_000L, MapCoordinateUtils.normalizeTimestampToMs(1_000_000_000_000L))
    }

    @Test
    fun appendStreamedPointIfNewer_rejectsOlderAndDuplicateLastPoint() {
        val coords = mutableListOf<List<Double>>(
            listOf(10.0, 20.0, 1_700_000_000_000.0)
        )
        assertFalse(MapCoordinateUtils.appendStreamedPointIfNewer(coords, 9.0, 19.0, 1_699_999_999_000L))
        assertFalse(MapCoordinateUtils.appendStreamedPointIfNewer(coords, 10.0, 20.0, 1_700_000_000_000L))
        assertTrue(MapCoordinateUtils.appendStreamedPointIfNewer(coords, 11.0, 21.0, 1_700_000_001_000L))
        assertEquals(2, coords.size)
    }

    @Test
    fun mergeNewerPointsInto_appendsOnlyProgressingPoints() {
        val target = mutableListOf<List<Double>>(
            listOf(1.0, 2.0, 1_700_000_000_000.0)
        )
        val source = listOf(
            listOf(1.0, 2.0, 1_700_000_000_000.0),
            listOf(0.0, 0.0, 1_699_999_999_000.0),
            listOf(3.0, 4.0, 1_700_000_001_000.0)
        )
        MapCoordinateUtils.mergeNewerPointsInto(target, source)
        assertEquals(2, target.size)
        assertEquals(listOf(3.0, 4.0, 1_700_000_001_000.0), target.last())
    }

    @Test
    fun appendStreamedPointIfNewer_keepsMaxPointsSlidingWindow() {
        val coords = MutableList(TrackUpdateHelper.MAX_POINTS) { idx ->
            listOf(idx.toDouble(), idx.toDouble(), (1_700_000_000_000L + idx).toDouble())
        }
        val accepted = MapCoordinateUtils.appendStreamedPointIfNewer(
            coords = coords,
            lon = 9999.0,
            lat = 9999.0,
            timestampMs = 1_700_000_100_000L
        )
        assertTrue(accepted)
        assertEquals(TrackUpdateHelper.MAX_POINTS, coords.size)
        assertEquals(listOf(1.0, 1.0, 1_700_000_000_001.0), coords.first())
    }

    @Test
    fun normalizeRawCoordinates_normalizesSecondsTimestamps() {
        val normalized = MapCoordinateUtils.normalizeRawCoordinates(
            listOf(
                listOf(10.0, 20.0, 1_000.0),
                listOf(11.0, 21.0, 2_000_000_000.0)
            )
        )
        assertEquals(2, normalized.size)
        assertEquals(1_000_000.0, normalized[0][2], 0.0)
        assertEquals(2_000_000_000_000.0, normalized[1][2], 0.0)
    }

    @Test
    fun normalizeRawCoordinates_supportsTwoElementCoordsAndTrimsToMaxPoints() {
        val oversized = (0..TrackUpdateHelper.MAX_POINTS).map { idx ->
            listOf(idx.toDouble(), idx.toDouble())
        }
        val normalized = MapCoordinateUtils.normalizeRawCoordinates(oversized)
        assertEquals(TrackUpdateHelper.MAX_POINTS, normalized.size)
        assertEquals(listOf(1.0, 1.0, 0.0), normalized.first())
        assertEquals(listOf(1000.0, 1000.0, 0.0), normalized.last())
    }

    @Test
    fun timestampFromCoordinateMs_handlesMissingTsAndBoundary() {
        assertNull(MapCoordinateUtils.timestampFromCoordinateMs(listOf(10.0, 20.0)))
        assertEquals(42L, MapCoordinateUtils.timestampFromCoordinateMs(listOf(10.0, 20.0), 42L))
        assertEquals(999_999_999_999_000L, MapCoordinateUtils.timestampFromCoordinateMs(listOf(10.0, 20.0, 999_999_999_999.0)))
        assertEquals(1_000_000_000_000L, MapCoordinateUtils.timestampFromCoordinateMs(listOf(10.0, 20.0, 1_000_000_000_000.0)))
    }
}
