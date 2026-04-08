package com.geovault.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ui.components.GeoVaultCompactDismissTitleBar
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.Tracker

data class GroupMemberRow(
    val trackerId: String,
    val tracker: Tracker?,
)

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
        backgroundColor = MaterialTheme.colors.surface,
        topBar = {
            GeoVaultCompactDismissTitleBar(
                title = group.name,
                onClose = onDismiss,
                closeContentDescription = stringResource(R.string.trackers_dialog_cancel),
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
                    )
                }
                GeoVaultPrimaryButton(
                    text = stringResource(R.string.trackers_action_view_group_on_map),
                    onClick = { onViewGroupOnMap(group.id) },
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 6.dp,
                    bottom = 12.dp,
                ),
            ) {
                items(
                    items = memberRows,
                    key = { it.trackerId },
                ) { row ->
                    GroupMemberCard(
                        row = row,
                        isHighlighted = row.trackerId == highlightedTrackerId,
                        borderColor = memberCardBorderColor,
                        onRowClick = { onViewTrackerOnMap(row.trackerId) },
                        onViewOnMap = { onViewTrackerOnMap(row.trackerId) },
                        onViewParams = row.tracker?.let { t -> { onViewTrackerParams(t) } },
                        onViewInList = if (onViewTrackerInList != null && row.tracker?.isOwner() == true) {
                            { onViewTrackerInList(row.trackerId) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMemberCard(
    row: GroupMemberRow,
    isHighlighted: Boolean,
    borderColor: Color,
    onRowClick: () -> Unit,
    onViewOnMap: () -> Unit,
    onViewParams: (() -> Unit)?,
    onViewInList: (() -> Unit)?,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val chevronTint = remember(row.tracker?.color) {
        TrackerChevronStylePolicy.tintForTrackerColorHex(row.tracker?.color, context)
    }
    val cardBackground = if (isHighlighted) {
        MaterialTheme.colors.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colors.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick),
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = cardBackground,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = borderColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackerChevronIcon(
                tint = chevronTint,
                modifier = Modifier
                    .size(TrackerChevronStylePolicy.TrackerRowChevronSize)
                    .padding(end = 0.dp),
            )
            Text(
                text = row.tracker?.name ?: row.trackerId,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                color = GeoVaultColorTokens.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = GeoVaultColorTokens.PrimaryBlue,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        onViewOnMap()
                    }) {
                        Text(stringResource(R.string.trackers_action_view_on_map))
                    }
                    if (onViewParams != null) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onViewParams()
                        }) {
                            Text(stringResource(R.string.map_action_view_params))
                        }
                    }
                    if (onViewInList != null) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onViewInList()
                        }) {
                            Text(stringResource(R.string.map_action_view_in_list))
                        }
                    }
                }
            }
        }
    }
}
