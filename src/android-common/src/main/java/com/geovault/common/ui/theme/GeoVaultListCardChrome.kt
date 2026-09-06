package com.geovault.common.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared "stylesheet" for list row cards ([com.geovault.common.ui.components.GeoVaultPointFeatureCard],
 * survey file rows, etc.): fill, border, and optional click handling driven by
 * [GeoVaultListCardHighlightColors]. Layouts keep their own shape radius and padding.
 */
object GeoVaultListCardChrome {
    val StrokeWidth = 1.dp
    val EmphasisStrokeWidth = 2.dp
}

/**
 * Applies clipped fill, border, and an optional click handler using
 * [GeoVaultListCardHighlightColors]. [selected] uses the Purple100 list-row treatment;
 * [highlighted] uses the purple-200 imported-item treatment; [offline] forces a yellow border.
 */
@Composable
fun Modifier.geoVaultListCardChrome(
    highlighted: Boolean = false,
    selected: Boolean = false,
    offline: Boolean = false,
    shape: Shape,
    strokeWidth: Dp = if (selected || offline) {
        GeoVaultListCardChrome.EmphasisStrokeWidth
    } else {
        GeoVaultListCardChrome.StrokeWidth
    },
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier {
    val isLight = MaterialTheme.colors.isLight
    val surfaceColor = MaterialTheme.colors.surface
    val fillColor = if (selected) {
        GeoVaultListCardHighlightColors.selectedFillColor(
            selected = true,
            isLight = isLight,
            surfaceColor = surfaceColor,
        )
    } else {
        GeoVaultListCardHighlightColors.fillColor(
            highlighted = highlighted,
            isLight = isLight,
            surfaceColor = surfaceColor,
        )
    }
    val borderColor = if (offline) {
        GeoVaultListCardHighlightColors.emphasisBorderColor(offline = true)
    } else if (selected) {
        GeoVaultListCardHighlightColors.emphasisBorderColor(offline = false)
    } else {
        GeoVaultListCardHighlightColors.borderColor(highlighted = highlighted, isLight = isLight)
    }
    val base = clip(shape)
        .background(color = fillColor, shape = shape)
        .border(width = strokeWidth, color = borderColor, shape = shape)
    return if (onClick != null) {
        base.clickable(enabled = enabled, onClick = onClick)
    } else {
        base
    }
}
