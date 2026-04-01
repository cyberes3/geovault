package com.geovault.common.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.geovault.common.ui.modifier.dismissKeyboardOnOutsideTap

private fun lightScheme(): Colors = lightColors(
    primary = GeoVaultColorTokens.PrimaryBlue,
    primaryVariant = GeoVaultColorTokens.PrimaryBlueDark,
    secondary = GeoVaultColorTokens.PrimaryBlue,
    surface = GeoVaultColorTokens.Surface,
    background = GeoVaultColorTokens.ListBackground,
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
    MaterialTheme(
        colors = if (darkTheme) darkScheme() else lightScheme()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dismissKeyboardOnOutsideTap()
        ) {
            content()
        }
    }
}
