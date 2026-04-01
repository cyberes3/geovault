package com.geovault.places.ui.theme

import androidx.compose.runtime.Composable
import com.geovault.common.ui.theme.GeoVaultTheme

@Composable
fun PlacesTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    GeoVaultTheme(darkTheme = darkTheme, content = content)
}