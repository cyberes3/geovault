package com.geovault.common.ui.system

import androidx.annotation.ColorInt
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens

object GeoVaultSystemBars {
    @ColorInt val PRIMARY_BLUE: Int = GeoVaultColorTokens.MainBlue.toArgb()
    @ColorInt val DEFAULT_NAV_BG: Int = GeoVaultColorTokens.ListBackground.toArgb()
    @ColorInt private var lastStatusBarColor: Int = PRIMARY_BLUE
    @ColorInt private var lastNavigationBarColor: Int = DEFAULT_NAV_BG
    private var lastUseDarkStatusBarText: Boolean = false
    private var lastUseDarkNavigationBarIcons: Boolean = true

    fun shouldApplyChrome(
        @ColorInt statusBarColor: Int,
        @ColorInt navigationBarColor: Int,
        useDarkStatusBarText: Boolean,
        useDarkNavigationBarIcons: Boolean,
    ): Boolean {
        return statusBarColor != lastStatusBarColor ||
            navigationBarColor != lastNavigationBarColor ||
            useDarkStatusBarText != lastUseDarkStatusBarText ||
            useDarkNavigationBarIcons != lastUseDarkNavigationBarIcons
    }

    fun applyAppChrome(
        activity: ComponentActivity,
        @ColorInt statusBarColor: Int = PRIMARY_BLUE,
        @ColorInt navigationBarColor: Int = DEFAULT_NAV_BG,
        useDarkStatusBarText: Boolean = false,
        useDarkNavigationBarIcons: Boolean = true
    ) {
        lastStatusBarColor = statusBarColor
        lastNavigationBarColor = navigationBarColor
        lastUseDarkStatusBarText = useDarkStatusBarText
        lastUseDarkNavigationBarIcons = useDarkNavigationBarIcons
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
        applyAppChrome(
            activity = activity,
            statusBarColor = statusBarColor,
            navigationBarColor = lastNavigationBarColor,
            useDarkStatusBarText = useDarkStatusBarText,
            useDarkNavigationBarIcons = lastUseDarkNavigationBarIcons,
        )
    }
}
