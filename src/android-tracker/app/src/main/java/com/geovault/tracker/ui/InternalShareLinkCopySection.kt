package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.tracker.R

@Composable
fun InternalShareLinkCopySection(
    helpText: String,
    shareUrl: String?,
    enabled: Boolean,
    tooltip: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Text(
        text = helpText,
        style = MaterialTheme.typography.caption,
        color = geoVaultContentSecondaryColor(),
    )
    GeoVaultSecondaryButton(
        text = stringResource(R.string.trackers_action_copy_internal_share_link),
        onClick = {
            TrackerShareLinks.copy(
                context = context,
                shareUrl = shareUrl,
                toastMessage = context.getString(R.string.internal_share_link_clip_label),
            )
        },
        enabled = enabled && !shareUrl.isNullOrBlank(),
        tooltip = tooltip,
        modifier = modifier.fillMaxWidth(),
        centeredContent = {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.trackers_action_copy_internal_share_link),
                style = MaterialTheme.typography.button,
            )
        },
    )
}
