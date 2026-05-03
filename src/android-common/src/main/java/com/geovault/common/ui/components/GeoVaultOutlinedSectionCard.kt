package com.geovault.common.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor

/**
 * Rounded, primary-blue-outlined "titled card" for clustering related controls in a form.
 * Optional trailing icon-button slot for a card-level action (e.g. "add" for filter chips).
 *
 * Body is laid out in a [Column] with 8.dp gaps; caller controls padding by supplying its own
 * child composables.
 */
@Composable
fun GeoVaultOutlinedSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    trailingContentDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = GeoVaultColorTokens.MainBlue,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.overline.copy(
                    fontWeight = FontWeight.Bold,
                    color = geoVaultContentSecondaryColor(),
                ),
                modifier = Modifier.weight(1f),
            )
            if (trailingIcon != null && onTrailingClick != null) {
                GeoVaultIconButton(
                    onClick = onTrailingClick,
                    modifier = Modifier.size(36.dp),
                    tooltip = trailingContentDescription,
                ) {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = trailingContentDescription,
                        tint = GeoVaultColorTokens.MainBlue,
                    )
                }
            }
        }
        content()
    }
}
