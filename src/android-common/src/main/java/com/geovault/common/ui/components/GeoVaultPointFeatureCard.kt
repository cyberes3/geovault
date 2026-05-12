package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Shared card chrome for map-drawer-style lists (survey points, NGS stations, etc.): 12dp
 * rounded corners, 1dp primary-blue stroke, surface fill, full-width row click.
 *
 * When [highlighted] is `true`, light mode uses a [GeoVaultColorTokens.Purple200] fill (stronger
 * than [GeoVaultColorTokens.Purple100], which blended into white card surfaces) with a
 * [GeoVaultColorTokens.Purple500] border. Dark mode uses a translucent purple wash plus
 * [GeoVaultColorTokens.MainPurple] border so selection reads clearly on black, not a faint blue.
 */
@Composable
fun GeoVaultPointFeatureCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CardCornerRadius)
    val isLight = MaterialTheme.colors.isLight
    val fillColor: Color = when {
        highlighted && isLight -> GeoVaultColorTokens.Purple200
        highlighted -> GeoVaultColorTokens.MainPurple.copy(alpha = 0.26f)
        else -> MaterialTheme.colors.surface
    }
    val borderColor: Color = when {
        highlighted && isLight -> GeoVaultColorTokens.Purple500
        highlighted -> GeoVaultColorTokens.MainPurple
        else -> GeoVaultColorTokens.MainBlue
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color = fillColor, shape = shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = CardContentHorizontalPadding, vertical = CardContentVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val CardCornerRadius = 12.dp
private val CardContentHorizontalPadding = 12.dp
private val CardContentVerticalPadding = 6.dp
