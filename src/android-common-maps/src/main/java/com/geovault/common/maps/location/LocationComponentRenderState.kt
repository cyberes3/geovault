package com.geovault.common.maps.location

import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap

/**
 * Single source of truth for the MapLibre location component state requested by hosts.
 *
 * MapLibre owns the actual style layers, which can disappear during a basemap reload. Keeping
 * desired state outside the SDK component lets us restore visibility and camera mode after the
 * new style is bound without asking each screen to replay its own lifecycle events.
 */
internal class LocationComponentRenderState {
    var isEnabled: Boolean = false
        private set

    var cameraMode: Int = CameraMode.NONE
        private set

    private var styleGeneration: Long = 0L
    private var boundStyleGeneration: Long = UNBOUND_STYLE_GENERATION

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            cameraMode = CameraMode.NONE
        }
    }

    fun setCameraTracking(enabled: Boolean) {
        cameraMode = if (enabled) CameraMode.TRACKING else CameraMode.NONE
    }

    fun setCameraMode(cameraMode: Int) {
        this.cameraMode = cameraMode
    }

    fun markStyleBindingStale() {
        styleGeneration += 1L
    }

    fun markStyleBindingCurrent() {
        boundStyleGeneration = styleGeneration
    }

    fun shouldBindStyle(componentActivated: Boolean): Boolean {
        return !componentActivated || boundStyleGeneration != styleGeneration
    }

    fun describe(): String {
        return "desiredEnabled=$isEnabled desiredCameraMode=$cameraMode " +
            "styleGeneration=$styleGeneration boundStyleGeneration=$boundStyleGeneration"
    }

    fun applyTo(map: MapLibreMap) {
        LocationComponentHelper.setEnabled(map, isEnabled)
        if (isEnabled) {
            LocationComponentHelper.setCameraMode(map, cameraMode)
        }
    }

    private companion object {
        private const val UNBOUND_STYLE_GENERATION = -1L
    }
}
