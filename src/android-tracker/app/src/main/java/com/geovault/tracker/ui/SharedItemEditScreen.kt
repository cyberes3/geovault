package com.geovault.tracker.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ClipboardCopyHelper
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultRequestBottomTabsDisabled
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.Tracker

@Composable
fun SharedTrackerEditScreen(
    tracker: Tracker,
    canUnsubscribe: Boolean,
    canLeaveShare: Boolean,
    isUnsubscribePending: Boolean,
    isLeaveSharePending: Boolean,
    onDismiss: () -> Unit,
    onUnsubscribe: () -> Unit,
    onLeaveShare: () -> Unit,
) {
    GeoVaultRequestBottomTabsDisabled(shouldDisable = true)
    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        onBack = {
            onDismiss()
            true
        },
    )
    GeoVaultSubViewScaffold(
        backgroundColor = MaterialTheme.colors.surface,
        title = stringResource(R.string.shared_tracker_edit_title),
        onClose = onDismiss,
        onLeaveComposition = onDismiss,
        closeContentDescription = stringResource(R.string.close),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = tracker.name,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.trackers_meta_owner_line,
                    tracker.owner_email?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.trackers_badge_not_owner)
                ),
                style = MaterialTheme.typography.body2,
            )
            InternalShareLinkCopySection(
                helpText = stringResource(R.string.trackers_edit_internal_share_help),
                shareUrl = tracker.internal_share_url,
                enabled = !tracker.internal_share_url.isNullOrBlank(),
                tooltip = stringResource(R.string.tooltip_edit_tracker_copy_internal_link),
                modifier = Modifier.fillMaxWidth(),
            )
            if (canUnsubscribe) {
                SharedDestructiveActionRow(
                    label = stringResource(R.string.trackers_action_unsubscribe),
                    isPending = isUnsubscribePending,
                    onClick = onUnsubscribe,
                    tooltip = stringResource(R.string.tooltip_edit_shared_unsubscribe),
                )
            }
            if (canLeaveShare) {
                SharedDestructiveActionRow(
                    label = stringResource(R.string.trackers_action_leave_share),
                    isPending = isLeaveSharePending,
                    onClick = onLeaveShare,
                    tooltip = stringResource(R.string.tooltip_edit_shared_remove_from_share),
                )
            }
        }
    }
}

@Composable
fun SharedGroupEditScreen(
    group: Group,
    isLeavePending: Boolean,
    onDismiss: () -> Unit,
    onLeaveGroup: () -> Unit,
) {
    GeoVaultRequestBottomTabsDisabled(shouldDisable = true)
    val context = LocalContext.current
    val clipboardHelper = remember(context) { ClipboardCopyHelper(context) }
    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        onBack = {
            onDismiss()
            true
        },
    )
    GeoVaultSubViewScaffold(
        backgroundColor = MaterialTheme.colors.surface,
        title = stringResource(R.string.groups_edit_shared_title),
        onClose = onDismiss,
        onLeaveComposition = onDismiss,
        closeContentDescription = stringResource(R.string.close),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.trackers_meta_owner_line,
                    group.owner_email?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.trackers_badge_not_owner)
                ),
                style = MaterialTheme.typography.body2,
            )
            Text(
                text = stringResource(R.string.groups_edit_internal_share_help),
                style = MaterialTheme.typography.caption,
            )
            GeoVaultSecondaryButton(
                text = stringResource(R.string.trackers_action_copy_internal_share_link),
                onClick = {
                    copySharedItemLink(
                        context = context,
                        clipboardHelper = clipboardHelper,
                        shareUrl = group.internal_share_url,
                        label = context.getString(R.string.internal_share_link_clip_label),
                    )
                },
                enabled = !group.internal_share_url.isNullOrBlank(),
                tooltip = stringResource(R.string.tooltip_group_detail_copy_internal_link),
                modifier = Modifier.fillMaxWidth(),
            )
            SharedDestructiveActionRow(
                label = stringResource(R.string.trackers_action_leave_group),
                isPending = isLeavePending,
                onClick = onLeaveGroup,
                tooltip = stringResource(R.string.tooltip_edit_shared_group_leave),
            )
        }
    }
}

private fun copySharedItemLink(
    context: Context,
    clipboardHelper: ClipboardCopyHelper,
    shareUrl: String?,
    label: String,
) {
    if (shareUrl.isNullOrBlank()) return
    clipboardHelper.copyText(resolveSharedItemUrl(context, shareUrl), label)
}

private fun resolveSharedItemUrl(context: Context, shareUrl: String): String {
    val trimmed = shareUrl.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val baseUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
    if (baseUrl.isBlank()) return trimmed
    return if (trimmed.startsWith("/")) "$baseUrl$trimmed" else "$baseUrl/$trimmed"
}

@Composable
private fun SharedDestructiveActionRow(
    label: String,
    isPending: Boolean,
    onClick: () -> Unit,
    tooltip: String,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        GeoVaultSecondaryButton(
            text = label,
            onClick = onClick,
            enabled = !isPending,
            tooltip = tooltip,
            modifier = Modifier.fillMaxWidth(),
            accentColor = GeoVaultColorTokens.MainRed,
        )
        if (isPending) {
            GeoVaultLoadingSpinner(
                spinnerSize = 18.dp,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .size(24.dp),
            )
        }
    }
}
