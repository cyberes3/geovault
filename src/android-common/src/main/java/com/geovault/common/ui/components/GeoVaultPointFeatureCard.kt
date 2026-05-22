package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.geoVaultListCardChrome

/**
 * Shared card chrome for map-drawer-style lists (survey points, NGS stations, etc.): 12dp
 * rounded corners, 1dp primary-blue stroke, surface fill, full-width row click.
 *
 * Card chrome (fill, border, highlight palette) comes from [GeoVaultListCardChrome]; this
 * composable only fixes drawer-style padding and 12dp corners.
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .geoVaultListCardChrome(
                highlighted = highlighted,
                shape = shape,
                onClick = onClick,
                enabled = enabled,
            )
            .padding(horizontal = CardContentHorizontalPadding, vertical = CardContentVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val CardCornerRadius = 12.dp
private val CardContentHorizontalPadding = 12.dp
private val CardContentVerticalPadding = 6.dp
