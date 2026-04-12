package com.geovault.common.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.ui.modifier.dismissKeyboardOnOutsideTap
import com.geovault.common.ui.navigation.GeoVaultBackHandlerHost
import com.geovault.common.ui.system.GeoVaultSystemBars

private fun lightScheme(): Colors = lightColors(
    primary = GeoVaultColorTokens.PrimaryBlue,
    primaryVariant = GeoVaultColorTokens.PrimaryBlueDark,
    secondary = GeoVaultColorTokens.PrimaryBlue,
    surface = GeoVaultColorTokens.Surface,
    background = GeoVaultColorTokens.Background,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onSurface = GeoVaultColorTokens.TextPrimary,
    onBackground = GeoVaultColorTokens.TextPrimary,
    error = GeoVaultColorTokens.Error
)

private fun darkScheme(): Colors = darkColors(
    primary = GeoVaultColorTokens.PrimaryBlue,
    primaryVariant = GeoVaultColorTokens.PrimaryBlueDark,
    secondary = GeoVaultColorTokens.PrimaryBlue,
    surface = GeoVaultColorTokens.DarkSurface,
    background = GeoVaultColorTokens.DarkBackground,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onSurface = GeoVaultColorTokens.DarkOnSurface,
    onBackground = GeoVaultColorTokens.DarkOnBackground,
    error = GeoVaultColorTokens.DarkError
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .dismissKeyboardOnOutsideTap()
            ) {
                content()
            }
        }
    }
}
