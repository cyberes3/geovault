package com.geovault.common.ui.snackbar

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

data class GeoVaultSnackbarStyle(
    val background: Color,
    val messageColor: Color,
    val actionColor: Color,
    val borderColor: Color,
    val borderWidth: Dp,
    val cornerRadius: Dp
)

object GeoVaultSnackbarDefaults {
    @Composable
    fun style(): GeoVaultSnackbarStyle {
        val dark = !MaterialTheme.colors.isLight
        return GeoVaultSnackbarStyle(
            background = if (dark) {
                GeoVaultColorTokens.Dark.SnackbarSurface
            } else {
                GeoVaultColorTokens.SnackbarSurface
            },
            messageColor = GeoVaultColorTokens.SnackbarMessage,
            actionColor = GeoVaultColorTokens.MainYellow,
            borderColor = GeoVaultColorTokens.Purple500,
            borderWidth = 1.dp,
            cornerRadius = 3.dp,
        )
    }
}
