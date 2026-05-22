package com.geovault.common.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.R
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.GeoVaultListCardHighlightColors

@Composable
fun GeoVaultTrailingListInfoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    tooltip: String? = null,
) {
    val resolvedTooltip = tooltip ?: stringResource(R.string.gv_common_tooltip_info)
    GeoVaultIconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp),
        enabled = enabled,
        tooltip = resolvedTooltip,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = resolvedTooltip,
            tint = GeoVaultListCardHighlightColors.trailingActionTint(
                highlighted = highlighted,
                defaultTint = GeoVaultColorTokens.MainBlue,
            ),
            modifier = Modifier.size(22.dp),
        )
    }
}
