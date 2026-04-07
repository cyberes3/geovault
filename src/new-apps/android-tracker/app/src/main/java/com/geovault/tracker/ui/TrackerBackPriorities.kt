package com.geovault.tracker.ui

/**
 * Higher values run first in [com.geovault.common.ui.navigation.GeoVaultBackRegistry].
 */
object TrackerBackPriorities {
    const val ROOT_TAB_BACK = 0
    const val ROOT_MAP_RETURN = 10
    const val SHARED_OVERLAY = 50
    const val FULL_SCREEN_OVERLAY = 100
    const val NESTED_FULL_SCREEN_OVERLAY = 110
}
