package com.geovault.common.maps.location

import com.geovault.common.maps.R
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.maplibre.android.location.modes.RenderMode

object GeoVaultLocationPuckPresets {
    fun blueUserLocation(
        accuracyAlpha: Float = 0.25f,
    ): LocationComponentHelper.Config {
        return LocationComponentHelper.Config(
            accuracyColor = GeoVaultColorTokens.PRIMARY_BLUE_INT,
            accuracyAlpha = accuracyAlpha,
            backgroundDrawable = R.drawable.gv_common_ic_user_location,
            foregroundDrawable = R.drawable.gv_common_ic_user_location,
            iconScale = 0.8f,
            renderMode = RenderMode.NORMAL,
        )
    }
}
