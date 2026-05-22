package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.GeoVaultListCardHighlightColors

/**
 * 40dp circular leading tile: in light theme, a blue or purple disk behind a tinted icon
 * (purple when [highlighted], matching [GeoVaultPointFeatureCard]); in dark theme the disk is
 * transparent so only the icon shows. Optional [onClick]
 * makes the tile a separate hit target from the enclosing row. [tileClickEnabled] mirrors row-
 * level chrome enablement (e.g. map not ready).
 *
 * When [onClick] is set, long-press tooltip text is [tooltip] if non-blank, otherwise
 * [contentDescription] when non-blank (vector/painter overloads only).
 */
@Composable
fun GeoVaultLeadingIconTile(
    icon: ImageVector,
    contentDescription: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    iconTint: Color? = null,
    tileClickEnabled: Boolean = true,
    tooltip: String? = null,
) {
    val tooltipHint = tooltip?.takeIf { it.isNotBlank() }
        ?: contentDescription?.takeIf { it.isNotBlank() }
    val resolvedTint = iconTint ?: GeoVaultListCardHighlightColors.iconTint(highlighted)
    GeoVaultLeadingIconTileImpl(
        modifier = modifier,
        onClick = onClick,
        tileClickEnabled = tileClickEnabled,
        tooltipHint = tooltipHint,
        highlighted = highlighted,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = Modifier.size(IconSize),
        )
    }
}

@Composable
fun GeoVaultLeadingIconTile(
    painter: Painter,
    contentDescription: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    iconTint: Color? = null,
    tileClickEnabled: Boolean = true,
    tooltip: String? = null,
) {
    val tooltipHint = tooltip?.takeIf { it.isNotBlank() }
        ?: contentDescription?.takeIf { it.isNotBlank() }
    val resolvedTint = iconTint ?: GeoVaultListCardHighlightColors.iconTint(highlighted)
    GeoVaultLeadingIconTileImpl(
        modifier = modifier,
        onClick = onClick,
        tileClickEnabled = tileClickEnabled,
        tooltipHint = tooltipHint,
        highlighted = highlighted,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = Modifier.size(IconSize),
        )
    }
}

@Composable
fun GeoVaultLeadingIconTile(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    tileClickEnabled: Boolean = true,
    tooltip: String? = null,
    content: @Composable () -> Unit,
) {
    GeoVaultLeadingIconTileImpl(
        modifier = modifier,
        onClick = onClick,
        tileClickEnabled = tileClickEnabled,
        tooltipHint = tooltip?.takeIf { it.isNotBlank() },
        highlighted = highlighted,
        icon = content,
    )
}

@Composable
private fun GeoVaultLeadingIconTileImpl(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    tileClickEnabled: Boolean,
    tooltipHint: String?,
    highlighted: Boolean = false,
    icon: @Composable () -> Unit,
) {
    val diskFill = GeoVaultListCardHighlightColors.iconTileDiskColor(highlighted)
    val base = modifier
        .size(TileSize)
        .clip(CircleShape)
        .background(color = diskFill, shape = CircleShape)
    when {
        onClick == null -> {
            Box(modifier = base, contentAlignment = Alignment.Center) {
                icon()
            }
        }
        tooltipHint.isNullOrBlank() -> {
            Box(
                modifier = base.clickable(enabled = tileClickEnabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
        else -> {
            GeoVaultClickableWithTooltip(
                onClick = onClick,
                modifier = base,
                enabled = tileClickEnabled,
                tooltip = tooltipHint,
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
    }
}

private val TileSize = 40.dp
private val IconSize = 24.dp
