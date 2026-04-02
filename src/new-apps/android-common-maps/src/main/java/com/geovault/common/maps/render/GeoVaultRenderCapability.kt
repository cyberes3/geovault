package com.geovault.common.maps.render

/**
 * Capability contract for style-bound render plugins.
 */
interface GeoVaultRenderCapability {
    fun setRenderState(newState: MapRenderState)
}
