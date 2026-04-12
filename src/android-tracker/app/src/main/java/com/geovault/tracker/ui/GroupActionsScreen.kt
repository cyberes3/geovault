package com.geovault.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultCompactDismissTitleBar
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.ui.components.GroupMemberRow
import com.geovault.tracker.ui.components.GroupMembersList

@Composable
fun GroupActionsScreen(
    group: Group,
    allTrackers: List<Tracker>,
    highlightedTrackerId: String?,
    onDismiss: () -> Unit,
    onViewTrackerOnMap: (trackerId: String) -> Unit,
    onViewTrackerParams: (Tracker) -> Unit,
    onViewTrackerInList: ((String) -> Unit)?,
    onEditGroup: (Group) -> Unit,
    onViewGroupOnMap: (groupId: String) -> Unit,
) {
    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        onBack = {
            onDismiss()
            true
        },
    )
    val context = LocalContext.current
    val byId = remember(allTrackers) { allTrackers.associateBy { it.id } }
    val memberRows = remember(group.track_ids, byId) {
        group.track_ids.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { id -> GroupMemberRow(trackerId = id, tracker = byId[id]) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(highlightedTrackerId, memberRows) {
        if (highlightedTrackerId != null) {
            val idx = memberRows.indexOfFirst { it.trackerId == highlightedTrackerId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
    }

    val actionBarBorderColor = if (isSystemInDarkTheme()) GeoVaultColorTokens.DarkBorderLight else GeoVaultColorTokens.BorderLight
    val memberCardBorderColor = GeoVaultColorTokens.PrimaryBlue

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        backgroundColor = MaterialTheme.colors.surface,
        topBar = {
            GeoVaultCompactDismissTitleBar(
                title = group.name,
                onClose = onDismiss,
                closeContentDescription = stringResource(R.string.trackers_dialog_cancel),
                closeTooltip = stringResource(R.string.tooltip_group_actions_close),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = actionBarBorderColor,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.isOwner()) {
                    GeoVaultSecondaryButton(
                        text = stringResource(R.string.trackers_dialog_edit_group_details_title),
                        onClick = { onEditGroup(group) },
                        tooltip = stringResource(R.string.tooltip_group_action_edit),
                    )
                }
                GeoVaultPrimaryButton(
                    text = stringResource(R.string.trackers_action_view_group_on_map),
                    onClick = { onViewGroupOnMap(group.id) },
                    tooltip = stringResource(R.string.tooltip_group_action_view_map),
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { innerPadding ->
        if (memberRows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.trackers_group_actions_empty),
                    style = MaterialTheme.typography.body2,
                    color = GeoVaultColorTokens.TextSecondary,
                )
            }
        } else {
            GroupMembersList(
                rows = memberRows,
                highlightedTrackerId = highlightedTrackerId,
                listState = listState,
                borderColor = memberCardBorderColor,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 6.dp,
                    bottom = 12.dp,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                onRowClick = { row -> onViewTrackerOnMap(row.trackerId) },
                onViewOnMap = { row -> onViewTrackerOnMap(row.trackerId) },
                onViewParams = { row -> row.tracker?.let(onViewTrackerParams) },
                onViewInList = if (onViewTrackerInList != null) {
                    { row ->
                        if (row.tracker?.isOwner() == true) {
                            onViewTrackerInList(row.trackerId)
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}
