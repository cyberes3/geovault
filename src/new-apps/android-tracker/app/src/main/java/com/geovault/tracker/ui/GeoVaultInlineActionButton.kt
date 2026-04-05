package com.geovault.tracker.ui

import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
internal fun GeoVaultInlineActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Text(
            text = text,
            color = if (enabled) {
                GeoVaultColorTokens.PrimaryBlue
            } else {
                GeoVaultColorTokens.TextSecondary
            }
        )
    }
}
