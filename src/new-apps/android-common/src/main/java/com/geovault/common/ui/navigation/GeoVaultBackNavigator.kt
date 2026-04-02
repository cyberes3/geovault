package com.geovault.common.ui.navigation

/**
 * Screen-owned policy object for in-app back navigation.
 */
interface GeoVaultBackNavigator {
    /**
     * Return true when this navigator can currently move back within its own UI state.
     */
    fun canGoBack(): Boolean

    /**
     * Attempt to move this navigator back.
     *
     * Return true only when the event was consumed.
     */
    fun goBack(): Boolean
}
