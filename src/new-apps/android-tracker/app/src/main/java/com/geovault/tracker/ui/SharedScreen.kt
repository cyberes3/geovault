package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.OwnershipActionPolicy
import com.geovault.tracker.presentation.SharedSubTab
import com.geovault.tracker.presentation.SharedUiState
import com.geovault.tracker.presentation.SharedViewModel
import com.geovault.tracker.presentation.TrackerLeaveKind

@Composable
fun SharedScreen(
    isAuthenticated: Boolean,
    serverUrl: String,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    isConnecting: Boolean,
    onOpenSettings: () -> Unit,
) {
    val vm: SharedViewModel = viewModel()
    val state by vm.uiState.collectAsState()

    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            vm.refreshAll()
        }
    }

    TrackerTabPlaceholderScreen(
        title = stringResource(R.string.shared_screen_title),
        placeholderText = stringResource(R.string.shared_placeholder_signed_out),
        isAuthenticated = isAuthenticated,
        serverUrl = serverUrl,
        onAuthServerUrlChanged = onAuthServerUrlChanged,
        onAuthConnect = onAuthConnect,
        isConnecting = isConnecting,
        onOpenSettings = onOpenSettings,
        scrollAuthenticatedMainContent = false,
        authenticatedMainContent = {
            SharedAuthenticatedBody(
                state = state,
                onSubTabSelected = vm::setSubTab,
                onRefresh = vm::refreshAll,
                onToggleTrackerMapHidden = vm::toggleTrackerHiddenOnMap,
                onToggleGroupMapHidden = vm::toggleGroupHiddenOnMap,
                onLeaveTracker = vm::leaveTracker,
                onLeaveGroup = vm::leaveGroup,
                onAcceptGroup = vm::acceptGroupShare,
                onSubscribeIncomingTracker = vm::subscribeIncomingTracker,
                onLeaveIncomingShare = vm::leaveIncomingShare,
                onSubscribePublicTracker = vm::subscribePublicTracker,
                onSubscribePublicGroup = vm::subscribePublicGroup,
                onUnsubscribeAllInGroup = vm::unsubscribeAllTracksInGroup,
                onDismissMessage = vm::clearUserMessage,
            )
        },
    )
}

@Composable
private fun ColumnScope.SharedAuthenticatedBody(
    state: SharedUiState,
    onSubTabSelected: (SharedSubTab) -> Unit,
    onRefresh: () -> Unit,
    onToggleTrackerMapHidden: (String) -> Unit,
    onToggleGroupMapHidden: (String) -> Unit,
    onLeaveTracker: (Tracker) -> Unit,
    onLeaveGroup: (String) -> Unit,
    onAcceptGroup: (String) -> Unit,
    onSubscribeIncomingTracker: (String) -> Unit,
    onLeaveIncomingShare: (String) -> Unit,
    onSubscribePublicTracker: (String) -> Unit,
    onSubscribePublicGroup: (List<String>) -> Unit,
    onUnsubscribeAllInGroup: (List<String>) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        val tabIndex = when (state.subTab) {
            SharedSubTab.SHARED -> 0
            SharedSubTab.DISCOVER -> 1
            SharedSubTab.PUBLIC -> 2
        }
        TabRow(selectedTabIndex = tabIndex) {
            Tab(
                selected = state.subTab == SharedSubTab.SHARED,
                onClick = { onSubTabSelected(SharedSubTab.SHARED) },
                text = { Text(stringResource(R.string.shared_subtab_shared)) },
            )
            Tab(
                selected = state.subTab == SharedSubTab.DISCOVER,
                onClick = { onSubTabSelected(SharedSubTab.DISCOVER) },
                text = { Text(stringResource(R.string.shared_subtab_discover)) },
            )
            Tab(
                selected = state.subTab == SharedSubTab.PUBLIC,
                onClick = { onSubTabSelected(SharedSubTab.PUBLIC) },
                text = { Text(stringResource(R.string.shared_subtab_public)) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.trackers_action_refresh))
            }
        }

        val userMessage = state.userMessage
        if (!userMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colors.surface,
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = userMessage,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissMessage) {
                        Text(stringResource(R.string.trackers_dismiss_message))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val showBlockingLoader = state.isLoading && !state.hasCompletedInitialLoad
        if (showBlockingLoader) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.subTab) {
                SharedSubTab.SHARED -> {
                    item {
                        SectionTitle(stringResource(R.string.shared_section_trackers_on_map))
                    }
                    if (state.visibleSharedTrackers.isEmpty()) {
                        item { EmptyLine(stringResource(R.string.shared_empty_shared_trackers)) }
                    } else {
                        items(state.visibleSharedTrackers, key = { "s-t-${it.id}" }) { tracker ->
                            SharedTrackerCard(
                                tracker = tracker,
                                hiddenOnMap = state.mapVisibility?.hidden_track_ids?.contains(tracker.id) == true,
                                onToggleMapHidden = { onToggleTrackerMapHidden(tracker.id) },
                                onLeave = { onLeaveTracker(tracker) },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        SectionTitle(stringResource(R.string.shared_section_groups_on_map))
                    }
                    if (state.visibleSharedGroups.isEmpty()) {
                        item { EmptyLine(stringResource(R.string.shared_empty_shared_groups)) }
                    } else {
                        items(state.visibleSharedGroups, key = { "s-g-${it.id}" }) { group ->
                            SharedMemberGroupCard(
                                group = group,
                                hiddenOnMap = state.mapVisibility?.hidden_group_ids?.contains(group.id) == true,
                                onToggleMapHidden = { onToggleGroupMapHidden(group.id) },
                                onLeave = { onLeaveGroup(group.id) },
                                onUnsubscribeAll = {
                                    onUnsubscribeAllInGroup(group.track_ids.orEmpty())
                                },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                }
                SharedSubTab.DISCOVER -> {
                    item {
                        SectionTitle(stringResource(R.string.shared_section_incoming_trackers))
                    }
                    if (state.incomingTrackers.isEmpty()) {
                        item { EmptyLine(stringResource(R.string.shared_empty_incoming_trackers)) }
                    } else {
                        items(state.incomingTrackers, key = { "d-t-${it.id}" }) { item ->
                            DiscoverIncomingTrackerCard(
                                item = item,
                                onAdd = { onSubscribeIncomingTracker(item.id) },
                                onRejectShare = { onLeaveIncomingShare(item.id) },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        SectionTitle(stringResource(R.string.shared_section_incoming_groups))
                    }
                    if (state.incomingGroups.isEmpty()) {
                        item { EmptyLine(stringResource(R.string.shared_empty_incoming_groups)) }
                    } else {
                        items(state.incomingGroups, key = { "d-g-${it.id}" }) { group ->
                            DiscoverIncomingGroupCard(
                                group = group,
                                onAccept = { onAcceptGroup(group.id) },
                                onLeave = { onLeaveGroup(group.id) },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                }
                SharedSubTab.PUBLIC -> {
                    item {
                        SectionTitle(stringResource(R.string.shared_section_public_trackers))
                    }
                    if (state.publicDiscoverTrackers.isEmpty()) {
                        item { EmptyLine(stringResource(R.string.shared_empty_public_trackers)) }
                    } else {
                        items(state.publicDiscoverTrackers, key = { "p-t-${it.id}" }) { item ->
                            PublicAddTrackerCard(
                                item = item,
                                onAdd = { onSubscribePublicTracker(item.id) },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        SectionTitle(stringResource(R.string.shared_section_public_groups))
                    }
                    if (state.publicDiscoverGroups.isEmpty()) {
                        item { EmptyLine(stringResource(R.string.shared_empty_public_groups)) }
                    } else {
                        items(state.publicDiscoverGroups, key = { "p-g-${it.id}" }) { group ->
                            PublicAddGroupCard(
                                group = group,
                                onAddGroup = { onSubscribePublicGroup(group.track_ids) },
                                enabled = !state.isLoading,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.overline,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun SharedTrackerCard(
    tracker: Tracker,
    hiddenOnMap: Boolean,
    onToggleMapHidden: () -> Unit,
    onLeave: () -> Unit,
    enabled: Boolean,
) {
    val leaveKind = OwnershipActionPolicy.trackerLeaveKind(tracker)
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = tracker.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
            val subtitle = listOfNotNull(
                tracker.visibility,
                tracker.owner_email?.let { stringResource(R.string.trackers_meta_owner_line, it) },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.caption)
            }
            Text(
                text = stringResource(R.string.trackers_badge_not_owner),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleMapHidden, enabled = enabled) {
                    Text(
                        if (hiddenOnMap) {
                            stringResource(R.string.trackers_action_show_on_map)
                        } else {
                            stringResource(R.string.trackers_action_hide_on_map)
                        },
                    )
                }
                if (leaveKind != null) {
                    TextButton(onClick = onLeave, enabled = enabled) {
                        Text(
                            when (leaveKind) {
                                TrackerLeaveKind.Unsubscribe -> stringResource(R.string.trackers_action_unsubscribe)
                                TrackerLeaveKind.LeaveShare -> stringResource(R.string.trackers_action_leave_share)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedMemberGroupCard(
    group: Group,
    hiddenOnMap: Boolean,
    onToggleMapHidden: () -> Unit,
    onLeave: () -> Unit,
    onUnsubscribeAll: () -> Unit,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = group.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
            val subtitle = listOfNotNull(
                group.visibility,
                group.owner_email?.let { stringResource(R.string.trackers_meta_owner_line, it) },
                group.track_ids?.size?.let { stringResource(R.string.trackers_meta_tracks_count, it) },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.caption)
            }
            Text(
                text = stringResource(R.string.trackers_badge_member),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleMapHidden, enabled = enabled) {
                    Text(
                        if (hiddenOnMap) {
                            stringResource(R.string.trackers_action_show_on_map)
                        } else {
                            stringResource(R.string.trackers_action_hide_on_map)
                        },
                    )
                }
                if (!group.track_ids.isNullOrEmpty()) {
                    TextButton(onClick = onUnsubscribeAll, enabled = enabled) {
                        Text(stringResource(R.string.shared_action_unsubscribe_all_tracks))
                    }
                }
                TextButton(onClick = onLeave, enabled = enabled) {
                    Text(stringResource(R.string.trackers_action_leave_group))
                }
            }
        }
    }
}

@Composable
private fun DiscoverIncomingTrackerCard(
    item: AvailableToAddItem,
    onAdd: () -> Unit,
    onRejectShare: () -> Unit,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = item.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
            item.owner_email?.takeIf { it.isNotBlank() }?.let { owner ->
                Text(
                    text = stringResource(R.string.trackers_meta_owner_line, owner),
                    style = MaterialTheme.typography.caption,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRejectShare, enabled = enabled) {
                    Text(stringResource(R.string.shared_action_reject_share))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onAdd, enabled = enabled) {
                    Text(stringResource(R.string.shared_action_add_to_trackers))
                }
            }
        }
    }
}

@Composable
private fun DiscoverIncomingGroupCard(
    group: AvailableToAddGroup,
    onAccept: () -> Unit,
    onLeave: () -> Unit,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = group.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
            group.owner_email?.takeIf { it.isNotBlank() }?.let { owner ->
                Text(
                    text = stringResource(R.string.trackers_meta_owner_line, owner),
                    style = MaterialTheme.typography.caption,
                )
            }
            Text(
                text = stringResource(R.string.trackers_badge_invite_pending),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onLeave, enabled = enabled) {
                    Text(stringResource(R.string.trackers_action_leave_group))
                }
                TextButton(onClick = onAccept, enabled = enabled) {
                    Text(stringResource(R.string.trackers_action_accept_invite))
                }
            }
        }
    }
}

@Composable
private fun PublicAddTrackerCard(
    item: AvailableToAddItem,
    onAdd: () -> Unit,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = item.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
            val meta = listOfNotNull(
                stringResource(R.string.shared_badge_public),
                item.owner_email?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.trackers_meta_owner_line, it) },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(text = meta, style = MaterialTheme.typography.caption)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onAdd, enabled = enabled) {
                    Text(stringResource(R.string.shared_action_add_to_trackers))
                }
            }
        }
    }
}

@Composable
private fun PublicAddGroupCard(
    group: AvailableToAddGroup,
    onAddGroup: () -> Unit,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = group.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
            val lines = buildList {
                group.owner_email?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.trackers_meta_owner_line, it)) }
                add(stringResource(R.string.shared_public_group_track_count, group.track_ids.size))
            }
            Text(text = lines.joinToString(" · "), style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onAddGroup, enabled = enabled && group.track_ids.isNotEmpty()) {
                    Text(stringResource(R.string.shared_action_add_group))
                }
            }
        }
    }
}
