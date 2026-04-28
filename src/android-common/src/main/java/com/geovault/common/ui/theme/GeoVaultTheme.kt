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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
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
            GeoVaultSystemBars.applyAppChrome(activity)
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
