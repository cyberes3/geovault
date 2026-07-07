package com.geovault.common.ui.system

import androidx.annotation.ColorInt
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens
import java.util.WeakHashMap

object GeoVaultSystemBars {
    @ColorInt val PRIMARY_BLUE: Int = GeoVaultColorTokens.MainBlue.toArgb()
    @ColorInt val DEFAULT_NAV_BG: Int = GeoVaultColorTokens.NavigationBarBackground.toArgb()

    /**
     * Fields start `null` ("never applied to this activity yet") rather than defaulting to
     * [PRIMARY_BLUE]/[DEFAULT_NAV_BG] — otherwise a brand-new activity whose first desired
     * chrome happens to equal those constants would make [shouldApplyChrome] report "no
     * change needed" and skip calling `enableEdgeToEdge` entirely, even though it was never
     * actually applied to that activity's window.
     */
    private class ChromeState {
        @ColorInt var statusBarColor: Int? = null
        @ColorInt var navigationBarColor: Int? = null
        var useDarkStatusBarText: Boolean? = null
        var useDarkNavigationBarIcons: Boolean? = null
    }

    // Keyed by Activity (weakly, so entries are collected automatically once an Activity
    // is destroyed) rather than stored in shared object-level fields. Each Activity/Window
    // gets its own independent "last applied" bookkeeping so one screen's chrome can never
    // leak into another's — e.g. a secondary Activity (edit/detail/settings) applying its
    // own default chrome must not corrupt what the main Activity thinks it last applied.
    private val stateByActivity = WeakHashMap<ComponentActivity, ChromeState>()

    fun shouldApplyChrome(
        activity: ComponentActivity,
        @ColorInt statusBarColor: Int,
        @ColorInt navigationBarColor: Int,
        useDarkStatusBarText: Boolean,
        useDarkNavigationBarIcons: Boolean,
    ): Boolean {
        val state = stateByActivity[activity] ?: return true
        return statusBarColor != state.statusBarColor ||
            navigationBarColor != state.navigationBarColor ||
            useDarkStatusBarText != state.useDarkStatusBarText ||
            useDarkNavigationBarIcons != state.useDarkNavigationBarIcons
    }

    fun applyAppChrome(
        activity: ComponentActivity,
        @ColorInt statusBarColor: Int = PRIMARY_BLUE,
        @ColorInt navigationBarColor: Int = DEFAULT_NAV_BG,
        useDarkStatusBarText: Boolean = false,
        useDarkNavigationBarIcons: Boolean = true
    ) {
        val state = stateByActivity.getOrPut(activity) { ChromeState() }
        state.statusBarColor = statusBarColor
        state.navigationBarColor = navigationBarColor
        state.useDarkStatusBarText = useDarkStatusBarText
        state.useDarkNavigationBarIcons = useDarkNavigationBarIcons
        activity.enableEdgeToEdge(
            statusBarStyle = if (useDarkStatusBarText) {
                SystemBarStyle.light(statusBarColor, statusBarColor)
            } else {
                SystemBarStyle.dark(statusBarColor)
            },
            navigationBarStyle = if (useDarkNavigationBarIcons) {
                SystemBarStyle.light(navigationBarColor, navigationBarColor)
            } else {
                SystemBarStyle.dark(navigationBarColor)
            }
        )
    }

    fun setNavigationBarBackground(
        activity: ComponentActivity,
        @ColorInt navigationBarColor: Int,
        @ColorInt statusBarColor: Int = PRIMARY_BLUE,
        useDarkStatusBarText: Boolean = false,
        useDarkNavigationBarIcons: Boolean = true
    ) {
        applyAppChrome(
            activity = activity,
            statusBarColor = statusBarColor,
            navigationBarColor = navigationBarColor,
            useDarkStatusBarText = useDarkStatusBarText,
            useDarkNavigationBarIcons = useDarkNavigationBarIcons
        )
    }

    fun setStatusBarBackground(
        activity: ComponentActivity,
        @ColorInt statusBarColor: Int,
        useDarkStatusBarText: Boolean = false
    ) {
        val state = stateByActivity[activity]
        applyAppChrome(
            activity = activity,
            statusBarColor = statusBarColor,
            navigationBarColor = state?.navigationBarColor ?: DEFAULT_NAV_BG,
            useDarkStatusBarText = useDarkStatusBarText,
            useDarkNavigationBarIcons = state?.useDarkNavigationBarIcons ?: true,
        )
    }
}
