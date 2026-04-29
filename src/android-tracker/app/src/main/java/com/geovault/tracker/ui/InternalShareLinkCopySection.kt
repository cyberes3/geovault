package com.geovault.tracker.ui

import android.content.Context
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ClipboardCopyHelper
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.theme.GeoVaultColorTokens
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
    val clipboardHelper = remember(context) { ClipboardCopyHelper(context) }
    Text(
        text = helpText,
        style = MaterialTheme.typography.caption,
        color = GeoVaultColorTokens.TextSecondary,
    )
    GeoVaultSecondaryButton(
        text = stringResource(R.string.trackers_action_copy_internal_share_link),
        onClick = {
            copyInternalShareLink(
                context = context,
                clipboardHelper = clipboardHelper,
                shareUrl = shareUrl,
                label = context.getString(R.string.internal_share_link_clip_label),
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

private fun copyInternalShareLink(
    context: Context,
    clipboardHelper: ClipboardCopyHelper,
    shareUrl: String?,
    label: String,
) {
    if (shareUrl.isNullOrBlank()) return
    clipboardHelper.copyText(resolveInternalShareUrl(context, shareUrl), label)
}

private fun resolveInternalShareUrl(context: Context, shareUrl: String): String {
    val trimmed = shareUrl.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val baseUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
    if (baseUrl.isBlank()) return trimmed
    return if (trimmed.startsWith("/")) "$baseUrl$trimmed" else "$baseUrl/$trimmed"
}
