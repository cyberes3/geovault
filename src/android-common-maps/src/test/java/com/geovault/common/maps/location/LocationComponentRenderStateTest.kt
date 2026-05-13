package com.geovault.common.maps.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.location.modes.CameraMode

class LocationComponentRenderStateTest {
    @Test
    fun setEnabled_falseClearsCameraMode() {
        val state = LocationComponentRenderState()

        state.setEnabled(true)
        state.setCameraMode(CameraMode.TRACKING_COMPASS)
        state.setEnabled(false)

        assertFalse(state.isEnabled)
        assertEquals(CameraMode.NONE, state.cameraMode)
    }

    @Test
    fun setCameraTracking_updatesDesiredCameraMode() {
        val state = LocationComponentRenderState()

        state.setCameraTracking(true)
        assertEquals(CameraMode.TRACKING, state.cameraMode)

        state.setCameraTracking(false)
        assertEquals(CameraMode.NONE, state.cameraMode)
    }

    @Test
    fun shouldBindStyle_requiresInitialBind() {
        val state = LocationComponentRenderState()

        assertTrue(state.shouldBindStyle(componentActivated = true))
    }

    @Test
    fun shouldBindStyle_tracksStyleGeneration() {
        val state = LocationComponentRenderState()

        state.markStyleBindingCurrent()
        assertFalse(state.shouldBindStyle(componentActivated = true))

        state.markStyleBindingStale()
        assertTrue(state.shouldBindStyle(componentActivated = true))
    }

    @Test
    fun shouldBindStyle_requiresBindWhenComponentIsNotActivated() {
        val state = LocationComponentRenderState()

        state.markStyleBindingCurrent()

        assertTrue(state.shouldBindStyle(componentActivated = false))
    }
}
