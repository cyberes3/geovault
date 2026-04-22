package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Canonical bold section label placed above a cluster of fields or a grouped list section.
 *
 * Consistent `subtitle1` + bold typography across screens. Use [topSpacing] for the gap from
 * the preceding block; pass `0.dp` when the header is the first element of a [androidx.compose.foundation.layout.Column].
 */
@Composable
fun GeoVaultFormSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    topSpacing: Dp = 0.dp,
) {
    if (topSpacing > 0.dp) {
        Spacer(modifier = Modifier.height(topSpacing))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
        color = GeoVaultColorTokens.TextPrimary,
        modifier = modifier,
    )
}
