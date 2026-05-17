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

/** Dialog title text: brand blue in light theme; light grey on dark dialog panels. */
@Composable
@ReadOnlyComposable
fun geoVaultDialogTitleColor(): Color =
    if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.MainBlue
    } else {
        GeoVaultColorTokens.Gray300
    }

/**
 * Non-destructive dialog actions (Cancel, Close, Save, Apply). Light theme uses brand blue;
 * dark theme uses a lighter blue for contrast on the grey dialog panel.
 */
@Composable
@ReadOnlyComposable
fun geoVaultDialogAccentButtonColor(): Color =
    if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.MainBlue
    } else {
        GeoVaultColorTokens.Blue300
    }

/**
 * Placeholder / hint text inside text fields and empty select triggers.
 *
 * Light theme uses [GeoVaultColorTokens.Gray400] (lighter than [geoVaultContentSecondaryColor]’s Gray600).
 * Dark theme uses [GeoVaultColorTokens.Gray300] so placeholders read clearly lighter than body secondary
 * (Gray400); using Gray400 in dark matched secondary and looked unchanged.
 */
@Composable
@ReadOnlyComposable
fun geoVaultInputPlaceholderColor(): Color =
    if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.Gray400
    } else {
        GeoVaultColorTokens.Gray300
    }

/** Text field label/title color: brand blue in light mode, light grey on dark input fills. */
@Composable
@ReadOnlyComposable
fun geoVaultInputLabelColor(): Color =
    if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.MainBlue
    } else {
        GeoVaultColorTokens.Gray300
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
            val statusBarColor = GeoVaultColorTokens.MainBlue.toArgb()
            val navigationBarColor = if (darkTheme) {
                GeoVaultColorTokens.Dark.ListBackground.toArgb()
            } else {
                GeoVaultColorTokens.ListBackground.toArgb()
            }
            val useDarkNavigationBarIcons = !darkTheme
            if (GeoVaultSystemBars.shouldApplyChrome(
                    statusBarColor = statusBarColor,
                    navigationBarColor = navigationBarColor,
                    useDarkStatusBarText = false,
                    useDarkNavigationBarIcons = useDarkNavigationBarIcons,
                )
            ) {
                GeoVaultSystemBars.applyAppChrome(
                    activity = activity,
                    statusBarColor = statusBarColor,
                    navigationBarColor = navigationBarColor,
                    useDarkNavigationBarIcons = useDarkNavigationBarIcons,
                )
            }
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
