package com.geovault.tracker.fragments.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapZoomOrchestratorTest {

    @Test
    fun zoomButtonsPaddingMode_followLockUsesCenteredPadding() {
        val mode = MapZoomOrchestrator.zoomButtonsPaddingMode(
            activeCameraIntent = CameraIntent.NONE,
            isFollowLockActive = true
        )
        assertEquals(CameraPaddingMode.CENTERED, mode)
    }

    @Test
    fun zoomButtonsPaddingMode_boundsFitUsesOverlayAwarePadding() {
        val mode = MapZoomOrchestrator.zoomButtonsPaddingMode(
            activeCameraIntent = CameraIntent.BOUNDS_FIT,
            isFollowLockActive = false
        )
        assertEquals(CameraPaddingMode.OVERLAY_AWARE, mode)
    }

    @Test
    fun boundsPaddingEdgesFromInsets_addsExtraPaddingPerEdge() {
        val edges = MapZoomOrchestrator.boundsPaddingEdgesFromInsets(
            insets = doubleArrayOf(10.0, 20.0, 30.0, 40.0),
            extraBoundsPaddingPx = 5
        )
        assertArrayEquals(intArrayOf(15, 25, 35, 45), edges)
    }

    @Test
    fun shouldApplyPaddingForCurrentMode_blocksWhenFollowLockActive() {
        val apply = MapZoomOrchestrator.shouldApplyPaddingForCurrentMode(
            allowCameraMove = true,
            isFollowLockActive = true,
            activeCameraIntent = CameraIntent.FOLLOW_LOCK,
            liveActiveFitEnabled = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            preserveCenteredAllTrackersFit = false
        )
        assertFalse(apply)
    }

    @Test
    fun shouldApplyPaddingForCurrentMode_allowsWhenUnlockedAndAllowed() {
        val apply = MapZoomOrchestrator.shouldApplyPaddingForCurrentMode(
            allowCameraMove = true,
            isFollowLockActive = false,
            activeCameraIntent = CameraIntent.NONE,
            liveActiveFitEnabled = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            preserveCenteredAllTrackersFit = false
        )
        assertTrue(apply)
    }
}
