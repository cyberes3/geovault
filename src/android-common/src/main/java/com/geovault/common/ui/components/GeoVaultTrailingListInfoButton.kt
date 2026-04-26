package com.geovault.common.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
fun GeoVaultTrailingListInfoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    GeoVaultIconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp),
        enabled = enabled,
        tooltip = "Info",
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Info",
            tint = GeoVaultColorTokens.MainBlue,
            modifier = Modifier.size(22.dp),
        )
    }
}
