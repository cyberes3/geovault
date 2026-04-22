package com.geovault.common.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Generic clickable list row: optional leading slot, title, optional subtitle, optional
 * trailing slot.
 *
 * Keeps spacing and typography consistent across list screens so apps don't reach for raw
 * [Row] + [Text] boilerplate. Callers decide whether to add a [androidx.compose.material.Divider]
 * between rows.
 */
@Composable
fun GeoVaultListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.SemiBold),
                color = GeoVaultColorTokens.TextPrimary,
            )
            val subtitleText = subtitle?.takeIf { it.isNotBlank() }
            if (subtitleText != null) {
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.body2,
                    color = GeoVaultColorTokens.TextSecondary,
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}
