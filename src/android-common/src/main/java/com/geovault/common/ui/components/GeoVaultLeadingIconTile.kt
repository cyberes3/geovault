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

/**
 * 40dp circular leading tile: in light theme, a [GeoVaultColorTokens.Blue100] disk behind a
 * tinted icon; in dark theme the disk is transparent so only the icon shows. Optional [onClick]
 * makes the tile a separate hit target from the enclosing row. [tileClickEnabled] mirrors row-
 * level chrome enablement (e.g. map not ready).
 */
@Composable
fun GeoVaultLeadingIconTile(
    icon: ImageVector,
    contentDescription: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconTint: Color = GeoVaultColorTokens.MainBlue,
    tileClickEnabled: Boolean = true,
) {
    GeoVaultLeadingIconTileImpl(
        modifier = modifier,
        onClick = onClick,
        tileClickEnabled = tileClickEnabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
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
    iconTint: Color = GeoVaultColorTokens.MainBlue,
    tileClickEnabled: Boolean = true,
) {
    GeoVaultLeadingIconTileImpl(
        modifier = modifier,
        onClick = onClick,
        tileClickEnabled = tileClickEnabled,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(IconSize),
        )
    }
}

@Composable
fun GeoVaultLeadingIconTile(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tileClickEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    GeoVaultLeadingIconTileImpl(
        modifier = modifier,
        onClick = onClick,
        tileClickEnabled = tileClickEnabled,
        icon = content,
    )
}

@Composable
private fun GeoVaultLeadingIconTileImpl(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    tileClickEnabled: Boolean,
    icon: @Composable () -> Unit,
) {
    val diskFill = if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.Blue100
    } else {
        Color.Transparent
    }
    val base = modifier
        .size(TileSize)
        .clip(CircleShape)
        .background(color = diskFill, shape = CircleShape)
    val tile = if (onClick != null) {
        base.clickable(enabled = tileClickEnabled, onClick = onClick)
    } else {
        base
    }
    Box(
        modifier = tile,
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

private val TileSize = 40.dp
private val IconSize = 24.dp
