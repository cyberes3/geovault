package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.GeoVaultLayoutTokens

/**
 * Optional call-to-action displayed beneath an empty-state message.
 */
data class GeoVaultEmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * Slim centered empty-state slot for lists and detail panes that have no data to show.
 *
 * Distinct from [GeoVaultStatusPane] (which is designed for loading/error/connected lifecycle
 * states with structured render models). Use this component when you just need "no results"
 * messaging with an optional icon, title, and primary action.
 *
 * Works in both full-screen and weighted slots because the outer [Column] is `fillMaxSize()`
 * with centered content.
 */
@Composable
fun GeoVaultEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    primaryAction: GeoVaultEmptyStateAction? = null,
    fillMaxSize: Boolean = true,
) {
    Column(
        modifier = modifier
            .then(if (fillMaxSize) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(GeoVaultLayoutTokens.PanePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = geoVaultContentSecondaryColor(),
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(GeoVaultLayoutTokens.StatusIconGap))
        }
        val heading = title?.takeIf { it.isNotBlank() }
        if (heading != null) {
            Text(
                text = heading,
                style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colors.onSurface,
            )
            Spacer(modifier = Modifier.height(GeoVaultLayoutTokens.StatusTitleGap))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.body2,
            color = geoVaultContentSecondaryColor(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val action = primaryAction
        if (action != null) {
            Spacer(modifier = Modifier.height(GeoVaultLayoutTokens.StatusActionGap))
            GeoVaultPrimaryButton(
                text = action.label,
                onClick = action.onClick,
                fitToContent = true,
            )
        }
    }
}
