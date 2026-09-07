package com.geovault.tracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.geovault.common.ui.time.GeoVaultDateTimeFormat
import com.geovault.common.ui.time.rememberNowMs
import com.geovault.tracker.policy.ActiveButDeadTrackerPolicy
import com.geovault.tracker.ui.TrackerPointTimestamps
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultIconButton
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultListCardChrome
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.ui.TrackerChevronIcon
import com.geovault.tracker.ui.TrackerChevronStylePolicy

data class GroupMemberRow(
    val trackerId: String,
    val tracker: Tracker?,
)

@Composable
fun GroupMembersList(
    rows: List<GroupMemberRow>,
    highlightedTrackerId: String?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onRowClick: (GroupMemberRow) -> Unit,
    onViewOnMap: (GroupMemberRow) -> Unit,
    onViewParams: ((GroupMemberRow) -> Unit)?,
    onViewInList: ((GroupMemberRow) -> Unit)?,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = contentPadding,
    ) {
        items(
            items = rows,
            key = { it.trackerId },
        ) { row ->
            GroupMemberCard(
                row = row,
                isHighlighted = row.trackerId == highlightedTrackerId,
                onRowClick = { onRowClick(row) },
                onViewOnMap = { onViewOnMap(row) },
                onViewParams = onViewParams?.let { callback -> { callback(row) } },
                onViewInList = onViewInList?.let { callback -> { callback(row) } },
            )
        }
    }
}

@Composable
private fun GroupMemberCard(
    row: GroupMemberRow,
    isHighlighted: Boolean,
    onRowClick: () -> Unit,
    onViewOnMap: () -> Unit,
    onViewParams: (() -> Unit)?,
    onViewInList: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val nowMs by rememberNowMs()
    val t = row.tracker
    val lastDataMs = t?.let(TrackerPointTimestamps::lastPointDataMs)
    val serverUpdatedAtMs = t?.let(TrackerPointTimestamps::serverMetadataUpdatedAtMs)
    val lastParamsMs = t?.let(TrackerPointTimestamps::lastPointParamsMs)
    val warnStaleData = t != null &&
        ActiveButDeadTrackerPolicy.isActiveButDead(
            nowMs = nowMs,
            updatedAtMs = serverUpdatedAtMs,
            lastDataMs = lastDataMs,
            lastParamsMs = lastParamsMs,
        )
    val lastLineText = if (lastDataMs != null) {
        GeoVaultDateTimeFormat.formatLocalDateTime(lastDataMs)
    } else {
        stringResource(R.string.waiting_for_data)
    }
    val lastLineColor = if (warnStaleData) {
        GeoVaultColorTokens.Error
    } else {
        geoVaultContentSecondaryColor()
    }
    val chevronTint = remember(row.tracker?.color) {
        TrackerChevronStylePolicy.tintForTrackerColorHex(row.tracker?.color)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .geoVaultListCardChrome(
                highlighted = isHighlighted,
                shape = RoundedCornerShape(8.dp),
                onClick = onRowClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            TrackerChevronIcon(
                tint = chevronTint,
                modifier = Modifier
                    .size(TrackerChevronStylePolicy.TrackerRowChevronSize)
                    .padding(end = 0.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = row.tracker?.name ?: row.trackerId,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = lastLineText,
                    style = MaterialTheme.typography.caption,
                    color = lastLineColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                GeoVaultIconButton(
                    onClick = { menuExpanded = true },
                    tooltip = stringResource(R.string.tooltip_group_tracker_menu),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = GeoVaultColorTokens.MainBlue,
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
