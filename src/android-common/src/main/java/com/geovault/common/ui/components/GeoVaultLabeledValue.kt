package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor

/**
 * Read-only "overline label + body value" detail-screen primitive. Use on point-detail,
 * coordinate-system-detail, and any other "field list" layout so typography and spacing stay
 * consistent.
 *
 * Pass [valueContent] to render rich content (links, chips, badges) in place of the plain text.
 */
@Composable
fun GeoVaultLabeledValue(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    valueContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.overline.copy(
                fontWeight = FontWeight.Bold,
                color = geoVaultContentSecondaryColor(),
            ),
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (valueContent != null) {
            valueContent()
        } else {
            Text(
                text = value.orEmpty(),
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onSurface,
            )
        }
    }
}
