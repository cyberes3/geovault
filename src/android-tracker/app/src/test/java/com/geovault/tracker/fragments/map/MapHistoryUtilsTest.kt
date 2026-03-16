package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapHistoryUtilsTest {
    @Test
    fun applyCoordinatesPreview_supportsTwoElementCoords() {
        val trackPoints = mutableListOf<LatLng>()
        val trackTimestamps = mutableListOf<Long>()

        val applied = MapHistoryUtils.applyCoordinatesPreview(
            coordinates = listOf(
                listOf(10.0, 20.0),
                listOf(11.0, 21.0)
            ),
            forceReplace = true,
            trackPoints = trackPoints,
            trackTimestamps = trackTimestamps
        )

        assertTrue(applied)
        assertEquals(2, trackPoints.size)
        assertEquals(2, trackTimestamps.size)
        assertEquals(LatLng(20.0, 10.0), trackPoints[0])
    }

    @Test
    fun applyGeometryToTrack_mergeExternalStreaming_preservesNewerStreamedPoints() {
        val trackPoints = mutableListOf(LatLng(1.0, 1.0))
        val trackTimestamps = mutableListOf(1_700_000_002_000L)

        MapHistoryUtils.applyGeometryToTrack(
            normalizedCoords = listOf(
                listOf(0.0, 0.0, 1_700_000_000_000.0),
                listOf(0.1, 0.1, 1_700_000_001_000.0)
            ),
            mergeExternalStreaming = true,
            trackPoints = trackPoints,
            trackTimestamps = trackTimestamps
        )

        assertEquals(3, trackPoints.size)
        assertEquals(LatLng(1.0, 1.0), trackPoints.last())
        assertEquals(1_700_000_002_000L, trackTimestamps.last())
    }
}
