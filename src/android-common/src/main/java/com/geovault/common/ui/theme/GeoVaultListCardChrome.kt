package com.geovault.common.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared "stylesheet" for list row cards ([com.geovault.common.ui.components.GeoVaultPointFeatureCard],
 * survey file rows, etc.): fill, border, and optional click handling driven by
 * [GeoVaultListCardHighlightColors]. Layouts keep their own shape radius and padding.
 */
object GeoVaultListCardChrome {
    val StrokeWidth = 1.dp
}

/**
 * Applies clipped fill, 1dp border, and an optional click handler using the standard
 * default vs purple-highlight palette from [GeoVaultListCardHighlightColors].
 */
@Composable
fun Modifier.geoVaultListCardChrome(
    highlighted: Boolean = false,
    shape: Shape,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier {
    val fillColor = GeoVaultListCardHighlightColors.fillColor(highlighted)
    val borderColor = GeoVaultListCardHighlightColors.borderColor(highlighted)
    val base = clip(shape)
        .background(color = fillColor, shape = shape)
        .border(width = GeoVaultListCardChrome.StrokeWidth, color = borderColor, shape = shape)
    return if (onClick != null) {
        base.clickable(enabled = enabled, onClick = onClick)
    } else {
        base
    }
}
