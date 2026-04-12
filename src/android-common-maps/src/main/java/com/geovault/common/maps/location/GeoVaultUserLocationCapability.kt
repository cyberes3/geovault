package com.geovault.common.maps.location

import android.location.Location

/**
 * Capability contract for user-location behavior that map UI can depend on without knowing
 * concrete plugin implementation details.
 */
interface GeoVaultUserLocationCapability {
    fun setEnabled(enabled: Boolean)
    fun setCameraTracking(enabled: Boolean)
    fun setAccuracyCircleVisible(visible: Boolean)
    fun isAccuracyCircleVisible(): Boolean
    fun renderLocation(location: Location)
}
