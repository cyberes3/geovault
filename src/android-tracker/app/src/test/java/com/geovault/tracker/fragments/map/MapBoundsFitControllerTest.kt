package com.geovault.tracker.fragments.map

import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapBoundsFitControllerTest {
    @Test
    fun selectBestEffortAtMinZoom_geoSpreadWithAustralia_doesNotCollapseToTwoPointCluster() {
        val representativePoints = listOf(
            "brazil1" to LatLng(-23.55, -46.63),
            "brazil2" to LatLng(-15.79, -47.88),
            "brazil3" to LatLng(-3.12, -60.02),
            "northDakota" to LatLng(47.55, -101.0),
            "texas" to LatLng(31.0, -99.0),
            "northMexico" to LatLng(29.0, -106.0),
            "bajaCalifornia" to LatLng(30.0, -115.0),
            "bulgaria" to LatLng(42.7, 25.5),
            "france" to LatLng(46.2, 2.2),
            "spain" to LatLng(40.4, -3.7),
            "africaAtlantic" to LatLng(14.7, -17.5),
            "australia" to LatLng(-25.27, 133.77)
        )

        val selection = MapBoundsFitController.selectBestEffortAtMinZoom(
            representativeTrackerPoints = representativePoints,
            boundsCenter = LatLng(20.0, -30.0),
            minZoom = 1.0,
            visibleWidthPx = 190.0,
            visibleHeightPx = 180.0
        )

        requireNotNull(selection)
        val ids = selection.includedTrackerIds.toSet()
        assertTrue("ids=$ids", ids.size >= 3)
        assertTrue("ids=$ids", ids != setOf("bulgaria", "australia"))
    }

    @Test
    fun selectBestEffortAtZoom_gpsAndTrackerUsesSharedMultiPointSelection() {
        val representativePoints = listOf(
            "tracker" to LatLng(31.0, -99.0),
            "gps" to LatLng(30.9, -99.1)
        )

        val selection = MapBoundsFitController.selectBestEffortAtZoom(
            representativeTrackerPoints = representativePoints,
            boundsCenter = LatLng(30.95, -99.05),
            zoom = 9.0,
            visibleWidthPx = 1200.0,
            visibleHeightPx = 900.0
        )

        requireNotNull(selection)
        assertTrue(selection.includedTrackerIds.contains("tracker"))
        assertTrue(selection.includedTrackerIds.contains("gps"))
    }

    @Test
    fun selectBestEffortAtZoom_wideWorld_withAtlanticAnchor_keepsMultiRegionCoverage() {
        val representativePoints = listOf(
            "brazil1" to LatLng(-23.55, -46.63),
            "brazil2" to LatLng(-15.79, -47.88),
            "northDakota" to LatLng(47.55, -101.0),
            "texas" to LatLng(31.0, -99.0),
            "bajaCalifornia" to LatLng(30.0, -115.0),
            "france" to LatLng(46.2, 2.2),
            "spain" to LatLng(40.4, -3.7),
            "africaAtlantic" to LatLng(14.7, -17.5),
            "australia" to LatLng(-25.27, 133.77)
        )

        val selection = MapBoundsFitController.selectBestEffortAtZoom(
            representativeTrackerPoints = representativePoints,
            boundsCenter = LatLng(20.0, -55.0),
            zoom = 2.0,
            visibleWidthPx = 520.0,
            visibleHeightPx = 420.0
        )

        requireNotNull(selection)
        val ids = selection.includedTrackerIds.toSet()
        assertTrue("ids=$ids", ids.size >= 4)
        assertTrue("ids=$ids", ids.any { it.startsWith("brazil") })
        assertTrue("ids=$ids", ids.any { it == "northDakota" || it == "texas" || it == "bajaCalifornia" })
    }

    @Test
    fun selectBestEffortAtZoom_wideWorld_zoomedOut_increasesCoverage() {
        val representativePoints = listOf(
            "brazil1" to LatLng(-23.55, -46.63),
            "brazil2" to LatLng(-15.79, -47.88),
            "brazil3" to LatLng(-3.12, -60.02),
            "northDakota" to LatLng(47.55, -101.0),
            "texas" to LatLng(31.0, -99.0),
            "northMexico" to LatLng(29.0, -106.0),
            "bajaCalifornia" to LatLng(30.0, -115.0),
            "france" to LatLng(46.2, 2.2),
            "spain" to LatLng(40.4, -3.7),
            "africaAtlantic" to LatLng(14.7, -17.5),
            "australia" to LatLng(-25.27, 133.77)
        )

        val atZoom2 = MapBoundsFitController.selectBestEffortAtZoom(
            representativeTrackerPoints = representativePoints,
            boundsCenter = LatLng(20.0, -55.0),
            zoom = 2.0,
            visibleWidthPx = 520.0,
            visibleHeightPx = 420.0
        )
        val atZoom16 = MapBoundsFitController.selectBestEffortAtZoom(
            representativeTrackerPoints = representativePoints,
            boundsCenter = LatLng(20.0, -55.0),
            zoom = 1.6,
            visibleWidthPx = 520.0,
            visibleHeightPx = 420.0
        )

        requireNotNull(atZoom2)
        requireNotNull(atZoom16)
        assertTrue(atZoom16.includedTrackerIds.size >= atZoom2.includedTrackerIds.size)
    }

    @Test
    fun selectBestEffortAtMinZoom_delegatesToSharedZoomSelector() {
        val representativePoints = listOf(
            "brazil" to LatLng(-23.55, -46.63),
            "spain" to LatLng(40.4, -3.7),
            "france" to LatLng(46.2, 2.2)
        )
        val center = LatLng(20.0, -20.0)
        val zoom = 2.0
        val width = 600.0
        val height = 400.0

        val atZoom = MapBoundsFitController.selectBestEffortAtZoom(
            representativeTrackerPoints = representativePoints,
            boundsCenter = center,
            zoom = zoom,
            visibleWidthPx = width,
            visibleHeightPx = height
        )
        val atMinZoom = MapBoundsFitController.selectBestEffortAtMinZoom(
            representativeTrackerPoints = representativePoints,
            boundsCenter = center,
            minZoom = zoom,
            visibleWidthPx = width,
            visibleHeightPx = height
        )

        requireNotNull(atZoom)
        requireNotNull(atMinZoom)
        assertTrue(atZoom.includedTrackerIds == atMinZoom.includedTrackerIds)
        assertTrue(atZoom.tieBreakReason == atMinZoom.tieBreakReason)
    }
}
