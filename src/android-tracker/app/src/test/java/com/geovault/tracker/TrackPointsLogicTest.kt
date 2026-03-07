package com.geovault.tracker

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the track points list update logic using [TrackUpdateHelper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackPointsLogicTest {

    @Test
    fun updateTrack_whenFull_appendsCorrectly() {
        val trackPoints = MutableList(1000) { LatLng(it.toDouble() / 100.0, it.toDouble() / 100.0) }
        val trackTimestamps = MutableList(1000) { it.toLong() }
        
        val newPoint = LatLng(15.0, 15.0)
        val newTimestamp = 1000L
        
        TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, newPoint, newTimestamp)
        
        assertEquals(newPoint, trackPoints.last())
        assertEquals(1000, trackPoints.size)
        assertEquals(1L, trackTimestamps[0]) // First point (timestamp 0) removed
        assertEquals(newTimestamp, trackTimestamps.last())
    }

    @Test
    fun updateTrack_gapFill_insertsInMiddle() {
        val trackPoints = mutableListOf(LatLng(0.0, 0.0), LatLng(2.0, 2.0))
        val trackTimestamps = mutableListOf(0L, 2L)
        
        val gapPoint = LatLng(1.0, 1.0)
        val gapTimestamp = 1L
        
        TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, gapPoint, gapTimestamp)
        
        assertEquals(3, trackPoints.size)
        assertEquals(gapPoint, trackPoints[1])
        assertEquals(gapTimestamp, trackTimestamps[1])
    }

    @Test
    fun updateTrack_outOfOrder_insertsCorrectly() {
        val trackPoints = mutableListOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))
        val trackTimestamps = mutableListOf(0L, 10L)
        
        val latePoint = LatLng(0.5, 0.5)
        val lateTimestamp = 5L
        
        TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, latePoint, lateTimestamp)
        
        assertEquals(3, trackPoints.size)
        assertEquals(latePoint, trackPoints[1])
        assertEquals(lateTimestamp, trackTimestamps[1])
    }
}
