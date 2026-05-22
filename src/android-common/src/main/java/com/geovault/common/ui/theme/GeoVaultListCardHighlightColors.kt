package com.geovault.common.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Transparent

/**
 * Fill and border colors for list rows that can show a transient purple "selected" state
 * (map drawer points, newly imported files, etc.).
 */
object GeoVaultListCardHighlightColors {
    fun fillColor(highlighted: Boolean, isLight: Boolean, surfaceColor: Color): Color = when {
        highlighted && isLight -> GeoVaultColorTokens.Purple200
        highlighted -> GeoVaultColorTokens.MainPurple.copy(alpha = 0.26f)
        else -> surfaceColor
    }

    fun borderColor(highlighted: Boolean, isLight: Boolean): Color = when {
        highlighted && isLight -> GeoVaultColorTokens.Purple500
        highlighted -> GeoVaultColorTokens.MainPurple
        else -> GeoVaultColorTokens.MainBlue
    }

    @Composable
    fun fillColor(highlighted: Boolean): Color =
        fillColor(
            highlighted = highlighted,
            isLight = MaterialTheme.colors.isLight,
            surfaceColor = MaterialTheme.colors.surface,
        )

    @Composable
    fun borderColor(highlighted: Boolean): Color =
        borderColor(highlighted = highlighted, isLight = MaterialTheme.colors.isLight)

    /** Light-mode disk behind [com.geovault.common.ui.components.GeoVaultLeadingIconTile]. */
    fun iconTileDiskColor(highlighted: Boolean, isLight: Boolean): Color = when {
        highlighted && isLight -> GeoVaultColorTokens.Purple100
        isLight -> GeoVaultColorTokens.Blue100
        else -> Transparent
    }

    fun iconTint(highlighted: Boolean): Color =
        if (highlighted) GeoVaultColorTokens.MainPurple else GeoVaultColorTokens.MainBlue

    /** Trailing row actions (info, overflow menu): purple when highlighted, else [defaultTint]. */
    fun trailingActionTint(highlighted: Boolean, defaultTint: Color): Color =
        if (highlighted) iconTint(highlighted = true) else defaultTint

    @Composable
    fun iconTileDiskColor(highlighted: Boolean): Color =
        iconTileDiskColor(highlighted = highlighted, isLight = MaterialTheme.colors.isLight)
}
