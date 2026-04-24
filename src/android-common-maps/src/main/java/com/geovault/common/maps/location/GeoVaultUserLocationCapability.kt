package com.geovault.common.maps.location

import android.location.Location

/**
 * Capability contract for user-location behavior that map UI can depend on without knowing
 * concrete plugin implementation details.
 */
interface GeoVaultUserLocationCapability {
    fun setEnabled(enabled: Boolean)
    fun setCameraTracking(enabled: Boolean)

    /**
     * Fine-grained camera-mode switch for host UIs that care about compass-following
     * (`TRACKING_COMPASS`) vs plain position tracking (`TRACKING`) vs no lock (`NONE`). Values
     * match `org.maplibre.android.location.modes.CameraMode`.
     */
    fun setCameraMode(cameraMode: Int) = Unit
    fun setAccuracyCircleVisible(visible: Boolean)
    fun isAccuracyCircleVisible(): Boolean
    fun renderLocation(location: Location)
}
