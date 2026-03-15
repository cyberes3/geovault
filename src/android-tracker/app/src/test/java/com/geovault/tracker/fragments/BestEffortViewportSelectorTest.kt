package com.geovault.tracker.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

class BestEffortViewportSelectorTest {
    @Test
    fun selectsAntimeridianClusterWhenItCoversMorePoints() {
        val points = listOf(
            ProjectedTrackerPoint("a1", 10.0, 170.0, 500.0, 210.0),
            ProjectedTrackerPoint("a2", 12.0, 176.0, 508.0, 205.0),
            ProjectedTrackerPoint("a3", 11.0, -176.0, 6.0, 208.0),
            ProjectedTrackerPoint("b1", 20.0, -10.0, 250.0, 210.0),
            ProjectedTrackerPoint("b2", 21.0, -6.0, 260.0, 212.0)
        )

        val selection = BestEffortViewportSelector.select(
            points = points,
            worldSize = 512.0,
            halfWidthPx = 35.0,
            halfHeightPx = 60.0,
            preferredCenterX = 256.0,
            preferredCenterY = 210.0
        )

        requireNotNull(selection)
        assertEquals(3, selection.includedTrackerIds.size)
        assertTrue(selection.includedTrackerIds.containsAll(listOf("a1", "a2", "a3")))
    }

    @Test
    fun clampsVerticalCenterToPhysicallyValidRange() {
        val points = listOf(
            ProjectedTrackerPoint("north", 80.0, 0.0, 150.0, 12.0),
            ProjectedTrackerPoint("mid", 0.0, 2.0, 152.0, 250.0),
            ProjectedTrackerPoint("south", -80.0, 4.0, 154.0, 500.0)
        )

        val halfHeight = 120.0
        val worldSize = 512.0
        val selection = BestEffortViewportSelector.select(
            points = points,
            worldSize = worldSize,
            halfWidthPx = 80.0,
            halfHeightPx = halfHeight,
            preferredCenterX = 150.0,
            preferredCenterY = 5.0
        )

        requireNotNull(selection)
        val minCenterY = halfHeight
        val maxCenterY = worldSize - halfHeight
        assertTrue(selection.centerY in minCenterY..maxCenterY)
    }

    @Test
    fun tieBreakIsDeterministicIndependentOfInputOrder() {
        val points = listOf(
            ProjectedTrackerPoint("left1", 0.0, -130.0, 50.0, 120.0),
            ProjectedTrackerPoint("left2", 0.5, -125.0, 70.0, 120.0),
            ProjectedTrackerPoint("right1", 1.0, 30.0, 300.0, 120.0),
            ProjectedTrackerPoint("right2", 1.5, 35.0, 320.0, 120.0)
        )

        val forward = BestEffortViewportSelector.select(
            points = points,
            worldSize = 512.0,
            halfWidthPx = 20.0,
            halfHeightPx = 60.0,
            preferredCenterX = 64.0,
            preferredCenterY = 120.0
        )
        val reversed = BestEffortViewportSelector.select(
            points = points.reversed(),
            worldSize = 512.0,
            halfWidthPx = 20.0,
            halfHeightPx = 60.0,
            preferredCenterX = 64.0,
            preferredCenterY = 120.0
        )

        requireNotNull(forward)
        requireNotNull(reversed)
        assertEquals(forward.centerX, reversed.centerX, 0.000001)
        assertEquals(forward.centerY, reversed.centerY, 0.000001)
        assertEquals(forward.includedTrackerIds, reversed.includedTrackerIds)
    }

    @Test
    fun recenterStepCentersWinningTrackersWithoutLosingCoverage() {
        val points = listOf(
            ProjectedTrackerPoint("a", 0.0, 0.0, 100.0, 200.0),
            ProjectedTrackerPoint("b", 0.0, 0.0, 130.0, 200.0),
            ProjectedTrackerPoint("c", 0.0, 0.0, 300.0, 200.0)
        )

        val selection = BestEffortViewportSelector.select(
            points = points,
            worldSize = 512.0,
            halfWidthPx = 40.0,
            halfHeightPx = 60.0,
            preferredCenterX = 90.0,
            preferredCenterY = 200.0
        )

        requireNotNull(selection)
        assertEquals(2, selection.includedTrackerIds.size)
        assertTrue(selection.includedTrackerIds.containsAll(listOf("a", "b")))
        assertEquals(115.0, selection.centerX, 0.000001)
    }

    @Test
    fun globalSpread_prefersNebraskaBrazilFrance_andExcludesTurkey() {
        val zoom = 1.0
        val worldSize = 256.0 * 2.0
        val points = listOf(
            fromLatLon("nebraska", 41.5, -99.8, zoom),
            fromLatLon("brazil", -14.2, -51.9, zoom),
            fromLatLon("france", 46.2, 2.2, zoom),
            fromLatLon("turkey", 39.0, 35.2, zoom)
        )

        // Keep viewport narrow enough that turkey competes with nebraska/brazil/france window.
        val selection = BestEffortViewportSelector.select(
            points = points,
            worldSize = worldSize,
            halfWidthPx = 80.0,
            halfHeightPx = 140.0,
            preferredCenterX = worldXAtZoom(0.0, zoom),
            preferredCenterY = worldYAtZoom(20.0, zoom)
        )

        requireNotNull(selection)
        assertEquals(3, selection.includedTrackerIds.size)
        assertTrue("ids=${selection.includedTrackerIds}", selection.includedTrackerIds.contains("nebraska"))
        assertTrue("ids=${selection.includedTrackerIds}", selection.includedTrackerIds.contains("brazil"))
        assertTrue("ids=${selection.includedTrackerIds}", selection.includedTrackerIds.contains("france"))
        assertTrue("ids=${selection.includedTrackerIds}", !selection.includedTrackerIds.contains("turkey"))

        val centerLon = worldXToLonDeg(selection.centerX, worldSize)
        // Expected center for nebraska/brazil/france window should be around Atlantic-facing middle, not near turkey.
        assertTrue(centerLon in -80.0..-10.0)
    }

    @Test
    fun moreThanFourTrackers_equalCoverage_recentersWinningSet() {
        val points = listOf(
            ProjectedTrackerPoint("w1", 0.0, 0.0, 40.0, 200.0),
            ProjectedTrackerPoint("w2", 0.0, 0.0, 70.0, 198.0),
            ProjectedTrackerPoint("w3", 0.0, 0.0, 100.0, 202.0),
            ProjectedTrackerPoint("w4", 0.0, 0.0, 130.0, 201.0),
            ProjectedTrackerPoint("o1", 0.0, 0.0, 260.0, 200.0),
            ProjectedTrackerPoint("o2", 0.0, 0.0, 400.0, 200.0)
        )

        val selection = BestEffortViewportSelector.select(
            points = points,
            worldSize = 512.0,
            halfWidthPx = 60.0,
            halfHeightPx = 80.0,
            preferredCenterX = 20.0,
            preferredCenterY = 200.0
        )

        requireNotNull(selection)
        assertEquals(4, selection.includedTrackerIds.size)
        assertTrue(selection.includedTrackerIds.containsAll(listOf("w1", "w2", "w3", "w4")))
        assertEquals(85.0, selection.centerX, 0.000001)
    }

    @Test
    fun antimeridianRecentering_preservesExactWinningIds() {
        val points = listOf(
            ProjectedTrackerPoint("a1", 0.0, 0.0, 500.0, 210.0),
            ProjectedTrackerPoint("a2", 0.0, 0.0, 508.0, 208.0),
            ProjectedTrackerPoint("a3", 0.0, 0.0, 6.0, 209.0),
            ProjectedTrackerPoint("o1", 0.0, 0.0, 250.0, 210.0),
            ProjectedTrackerPoint("o2", 0.0, 0.0, 330.0, 210.0)
        )

        val selection = BestEffortViewportSelector.select(
            points = points,
            worldSize = 512.0,
            halfWidthPx = 25.0,
            halfHeightPx = 80.0,
            preferredCenterX = 256.0,
            preferredCenterY = 210.0
        )

        requireNotNull(selection)
        val expected = setOf("a1", "a2", "a3")
        assertEquals(expected, selection.includedTrackerIds.toSet())
        assertTrue(selection.centerX < 20.0 || selection.centerX > 492.0)
    }

    private fun fromLatLon(id: String, lat: Double, lon: Double, zoom: Double): ProjectedTrackerPoint {
        return ProjectedTrackerPoint(
            trackerId = id,
            latitude = lat,
            longitude = lon,
            worldX = worldXAtZoom(lon, zoom),
            worldY = worldYAtZoom(lat, zoom)
        )
    }

    private fun worldXAtZoom(lonDeg: Double, zoom: Double): Double {
        val worldSize = 256.0 * 2.0.pow(zoom)
        var norm = ((lonDeg + 180.0) / 360.0) % 1.0
        if (norm < 0.0) norm += 1.0
        return norm * worldSize
    }

    private fun worldYAtZoom(latDeg: Double, zoom: Double): Double {
        val worldSize = 256.0 * 2.0.pow(zoom)
        val lat = latDeg.coerceIn(-85.05112878, 85.05112878)
        val latRad = lat * kotlin.math.PI / 180.0
        val mercN = ln(tan(kotlin.math.PI / 4.0 + latRad / 2.0))
        return (0.5 - mercN / (2.0 * kotlin.math.PI)) * worldSize
    }

    private fun worldXToLonDeg(x: Double, worldSize: Double): Double {
        var norm = (x / worldSize) % 1.0
        if (norm < 0.0) norm += 1.0
        return norm * 360.0 - 180.0
    }
}
