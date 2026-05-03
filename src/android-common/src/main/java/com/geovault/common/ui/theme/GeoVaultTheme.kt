package com.geovault.common.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.ui.modifier.dismissKeyboardOnOutsideTap
import com.geovault.common.ui.navigation.GeoVaultBackHandlerHost
import com.geovault.common.ui.system.GeoVaultSystemBars

private fun lightScheme(): Colors = lightColors(
    primary = GeoVaultColorTokens.MainBlue,
    primaryVariant = GeoVaultColorTokens.Blue600,
    secondary = GeoVaultColorTokens.MainBlue,
    surface = GeoVaultColorTokens.Surface,
    background = GeoVaultColorTokens.ListBackground,
    onPrimary = GeoVaultColorTokens.White,
    onSecondary = GeoVaultColorTokens.White,
    onSurface = GeoVaultColorTokens.TextPrimary,
    onBackground = GeoVaultColorTokens.TextPrimary,
    error = GeoVaultColorTokens.Error
)

/**
 * Secondary body / caption text that tracks light vs dark neutrals. Material 2 [Colors]
 * does not expose a dedicated on-surface-muted slot; this keeps one shared definition.
 */
@Composable
@ReadOnlyComposable
fun geoVaultContentSecondaryColor(): Color =
    if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.TextSecondary
    } else {
        GeoVaultColorTokens.Dark.TextSecondary
    }

/** Hairline separators (search / settings dividers): light blue tint vs dark neutral. */
@Composable
@ReadOnlyComposable
fun geoVaultHairlineDividerColor(): Color =
    if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.BorderLight
    } else {
        GeoVaultColorTokens.Dark.BorderLight
    }

/**
 * Outlined card chrome: light mode keeps the soft blue hairline ([GeoVaultColorTokens.BorderLight]);
 * dark mode uses [GeoVaultColorTokens.MainBlue] on pure-black surfaces.
 */
@Composable
@ReadOnlyComposable
fun geoVaultCardBorderColor(): Color =
    if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.BorderLight
    } else {
        GeoVaultColorTokens.MainBlue
    }

/** Filled area behind form text fields: light uses [MaterialTheme.colors.surface]; dark uses elevated grey. */
@Composable
@ReadOnlyComposable
fun geoVaultTextFieldFillColor(): Color =
    if (MaterialTheme.colors.isLight) {
        MaterialTheme.colors.surface
    } else {
        GeoVaultColorTokens.Dark.BlueLight
    }

/**
 * Panel fill for [androidx.compose.material.AlertDialog], [androidx.compose.ui.window.Dialog], and
 * other common popup shells — light grey in light theme; same elevated grey as inputs in dark.
 */
@Composable
@ReadOnlyComposable
fun geoVaultDialogSurfaceColor(): Color =
    if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.Gray100
    } else {
        GeoVaultColorTokens.Dark.BlueLight
    }

private fun darkScheme(): Colors = darkColors(
    primary = GeoVaultColorTokens.MainBlue,
    primaryVariant = GeoVaultColorTokens.Blue600,
    secondary = GeoVaultColorTokens.MainBlue,
    surface = GeoVaultColorTokens.Dark.Surface,
    background = GeoVaultColorTokens.Dark.ListBackground,
    onPrimary = GeoVaultColorTokens.White,
    onSecondary = GeoVaultColorTokens.White,
    onSurface = GeoVaultColorTokens.Dark.TextPrimary,
    onBackground = GeoVaultColorTokens.Dark.TextPrimary,
    error = GeoVaultColorTokens.Dark.Error
)

@Composable
fun GeoVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val activity = LocalContext.current as? ComponentActivity
    SideEffect {
        if (activity != null) {
            val navigationBarColor = if (darkTheme) {
                GeoVaultColorTokens.Dark.ListBackground.toArgb()
            } else {
                GeoVaultColorTokens.ListBackground.toArgb()
            }
            GeoVaultSystemBars.applyAppChrome(
                activity = activity,
                navigationBarColor = navigationBarColor,
                useDarkNavigationBarIcons = !darkTheme,
            )
        }
    }

    MaterialTheme(
        colors = if (darkTheme) darkScheme() else lightScheme()
    ) {
        GeoVaultBackHandlerHost {
            // Theme is intentionally inset-agnostic. Navigation-bar safe-area is owned by the
            // chrome that needs it (GeoVaultBottomNavScaffold, GeoVaultMapScaffold,
            // GeoVaultSubViewScaffold, GeoVaultAuthGate, GeoVaultSnackbarHost). Padding here
            // would force the map's GL surface to re-measure on every transient WindowInsets
            // dispatch (e.g. after screen-off → resume), producing a vertical squish.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .dismissKeyboardOnOutsideTap()
            ) {
                content()
            }
        }
    }
}
