package com.geovault.common.maps.ui.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultMapCameraInteractionGateTest {
    @Test
    fun panFlingRotate_unlockCamera() {
        assertTrue(GeoVaultMapCameraInteractionGate.unlocksCamera(GeoVaultMapCameraInteraction.Pan))
        assertTrue(GeoVaultMapCameraInteractionGate.unlocksCamera(GeoVaultMapCameraInteraction.Fling))
        assertTrue(GeoVaultMapCameraInteractionGate.unlocksCamera(GeoVaultMapCameraInteraction.Rotate))
    }

    @Test
    fun pinchAndProgrammaticZoom_keepLock() {
        assertFalse(GeoVaultMapCameraInteractionGate.unlocksCamera(GeoVaultMapCameraInteraction.PinchZoom))
        assertFalse(GeoVaultMapCameraInteractionGate.unlocksCamera(GeoVaultMapCameraInteraction.ProgrammaticZoom))
        assertTrue(GeoVaultMapCameraInteractionGate.ownsZoom(GeoVaultMapCameraInteraction.PinchZoom))
        assertTrue(GeoVaultMapCameraInteractionGate.ownsZoom(GeoVaultMapCameraInteraction.ProgrammaticZoom))
    }

    @Test
    fun panDoesNotOwnZoom() {
        assertFalse(GeoVaultMapCameraInteractionGate.ownsZoom(GeoVaultMapCameraInteraction.Pan))
        assertFalse(GeoVaultMapCameraInteractionGate.ownsZoom(GeoVaultMapCameraInteraction.Fling))
        assertFalse(GeoVaultMapCameraInteractionGate.ownsZoom(GeoVaultMapCameraInteraction.Rotate))
    }
}
