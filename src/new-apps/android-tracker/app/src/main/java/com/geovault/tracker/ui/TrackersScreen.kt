package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.OwnershipActionPolicy
import com.geovault.tracker.presentation.TrackerLeaveKind
import com.geovault.tracker.presentation.TrackersGroupsDialog
import com.geovault.tracker.presentation.TrackersGroupsSubTab
import com.geovault.tracker.presentation.TrackersGroupsUiState
import com.geovault.tracker.presentation.TrackersGroupsViewModel

@Composable
fun TrackersScreen(
    isAuthenticated: Boolean,
    serverUrl: String,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    isConnecting: Boolean,
    onOpenSettings: () -> Unit,
) {
    val vm: TrackersGroupsViewModel = viewModel()
    val state by vm.uiState.collectAsState()

    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            vm.refreshAll()
        }
    }

    TrackerTabPlaceholderScreen(
        title = stringResource(R.string.trackers_screen_title),
        placeholderText = stringResource(R.string.trackers_placeholder_signed_out),
        isAuthenticated = isAuthenticated,
        serverUrl = serverUrl,
        onAuthServerUrlChanged = onAuthServerUrlChanged,
        onAuthConnect = onAuthConnect,
        isConnecting = isConnecting,
        onOpenSettings = onOpenSettings,
        scrollAuthenticatedMainContent = false,
        authenticatedMainContent = {
            TrackersGroupsAuthenticatedBody(
                state = state,
                onSubTabSelected = vm::setSubTab,
                onRefresh = vm::refreshAll,
                onCreateTracker = vm::openCreateTrackerDialog,
                onCreateGroup = vm::openCreateGroupDialog,
                onToggleTrackerMapHidden = vm::toggleTrackerHiddenOnMap,
                onToggleGroupMapHidden = vm::toggleGroupHiddenOnMap,
                onLeaveTracker = vm::leaveTracker,
                onLeaveGroup = vm::leaveGroup,
                onAcceptGroup = vm::acceptGroupShare,
                onEditTracker = vm::openEditTrackerDialog,
                onEditGroup = vm::openEditGroupDialog,
                onDismissMessage = vm::clearUserMessage,
            )
        },
    )

    TrackersGroupsDialogs(
        dialog = state.dialog,
        onDismiss = vm::dismissDialog,
        onCreateTrackerDraft = vm::updateCreateTrackerDraft,
        onCreateTrackerSetAsSelected = vm::updateCreateTrackerSetAsSelected,
        onCreateGroupDraft = vm::updateCreateGroupDraft,
        onEditTrackerDraft = vm::updateEditTrackerDraft,
        onEditTrackerSetAsSelected = vm::updateEditTrackerSetAsSelected,
        onEditGroupDraft = vm::updateEditGroupDraft,
        onSubmitCreateTracker = vm::submitCreateTracker,
        onSubmitCreateGroup = vm::submitCreateGroup,
        onSubmitEditTracker = vm::submitEditTracker,
        onSubmitEditGroup = vm::submitEditGroup,
    )
}

@Composable
private fun TrackersGroupsAuthenticatedBody(
    state: TrackersGroupsUiState,
    onSubTabSelected: (TrackersGroupsSubTab) -> Unit,
    onRefresh: () -> Unit,
    onCreateTracker: () -> Unit,
    onCreateGroup: () -> Unit,
    onToggleTrackerMapHidden: (String) -> Unit,
    onToggleGroupMapHidden: (String) -> Unit,
    onLeaveTracker: (Tracker) -> Unit,
    onLeaveGroup: (String) -> Unit,
    onAcceptGroup: (String) -> Unit,
    onEditTracker: (Tracker) -> Unit,
    onEditGroup: (Group) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        val tabIndex = if (state.subTab == TrackersGroupsSubTab.TRACKERS) 0 else 1
        TabRow(selectedTabIndex = tabIndex) {
            Tab(
                selected = state.subTab == TrackersGroupsSubTab.TRACKERS,
                onClick = { onSubTabSelected(TrackersGroupsSubTab.TRACKERS) },
                text = { Text(stringResource(R.string.trackers_subtab_trackers)) },
            )
            Tab(
                selected = state.subTab == TrackersGroupsSubTab.GROUPS,
                onClick = { onSubTabSelected(TrackersGroupsSubTab.GROUPS) },
                text = { Text(stringResource(R.string.trackers_subtab_groups)) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.trackers_action_refresh))
            }
            OutlinedButton(
                onClick = {
                    if (state.subTab == TrackersGroupsSubTab.TRACKERS) onCreateTracker() else onCreateGroup()
                },
                enabled = !state.isLoading,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.subTab == TrackersGroupsSubTab.TRACKERS) {
                            stringResource(R.string.trackers_action_create_tracker)
                        } else {
                            stringResource(R.string.trackers_action_create_group)
                        },
                    )
                }
            }
        }

        if (!state.userMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
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
                        text = state.userMessage,
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

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.subTab) {
                TrackersGroupsSubTab.TRACKERS -> {
                    items(state.trackers, key = { it.id }) { tracker ->
                        TrackerRowCard(
                            tracker = tracker,
                            hiddenOnMap = state.mapVisibility?.hidden_track_ids?.contains(tracker.id) == true,
                            onToggleMapHidden = { onToggleTrackerMapHidden(tracker.id) },
                            onLeave = { onLeaveTracker(tracker) },
                            onEdit = { onEditTracker(tracker) },
                            enabled = !state.isLoading,
                        )
                    }
                }
                TrackersGroupsSubTab.GROUPS -> {
                    items(state.groups, key = { it.id }) { group ->
                        GroupRowCard(
                            group = group,
                            hiddenOnMap = state.mapVisibility?.hidden_group_ids?.contains(group.id) == true,
                            onToggleMapHidden = { onToggleGroupMapHidden(group.id) },
                            onLeave = { onLeaveGroup(group.id) },
                            onAccept = { onAcceptGroup(group.id) },
                            onEdit = { onEditGroup(group) },
                            enabled = !state.isLoading,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackerRowCard(
    tracker: Tracker,
    hiddenOnMap: Boolean,
    onToggleMapHidden: () -> Unit,
    onLeave: () -> Unit,
    onEdit: () -> Unit,
    enabled: Boolean,
) {
    val leaveKind = OwnershipActionPolicy.trackerLeaveKind(tracker)
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = tracker.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
                    val subtitle = listOfNotNull(
                        tracker.visibility,
                        tracker.owner_email?.let { stringResource(R.string.trackers_meta_owner_line, it) },
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(text = subtitle, style = MaterialTheme.typography.caption)
                    }
                    Text(
                        text = if (tracker.isOwner()) {
                            stringResource(R.string.trackers_badge_owner)
                        } else {
                            stringResource(R.string.trackers_badge_not_owner)
                        },
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
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
                if (OwnershipActionPolicy.canEditTracker(tracker)) {
                    TextButton(onClick = onEdit, enabled = enabled) {
                        Text(stringResource(R.string.trackers_action_edit))
                    }
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
private fun GroupRowCard(
    group: Group,
    hiddenOnMap: Boolean,
    onToggleMapHidden: () -> Unit,
    onLeave: () -> Unit,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    enabled: Boolean,
) {
    val pending = OwnershipActionPolicy.groupPendingAccept(group)
    Card(modifier = Modifier.fillMaxWidth(), elevation = 0.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
                    text = when {
                        pending -> stringResource(R.string.trackers_badge_invite_pending)
                        group.isOwner() -> stringResource(R.string.trackers_badge_owner)
                        else -> stringResource(R.string.trackers_badge_member)
                    },
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!pending) {
                    TextButton(onClick = onToggleMapHidden, enabled = enabled) {
                        Text(
                            if (hiddenOnMap) {
                                stringResource(R.string.trackers_action_show_on_map)
                            } else {
                                stringResource(R.string.trackers_action_hide_on_map)
                            },
                        )
                    }
                }
                if (OwnershipActionPolicy.canEditGroup(group)) {
                    TextButton(onClick = onEdit, enabled = enabled) {
                        Text(stringResource(R.string.trackers_action_edit))
                    }
                }
                if (pending) {
                    TextButton(onClick = onAccept, enabled = enabled) {
                        Text(stringResource(R.string.trackers_action_accept_invite))
                    }
                } else if (OwnershipActionPolicy.groupCanLeave(group)) {
                    TextButton(onClick = onLeave, enabled = enabled) {
                        Text(stringResource(R.string.trackers_action_leave_group))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackersGroupsDialogs(
    dialog: TrackersGroupsDialog,
    onDismiss: () -> Unit,
    onCreateTrackerDraft: (String, String) -> Unit,
    onCreateTrackerSetAsSelected: (Boolean) -> Unit,
    onCreateGroupDraft: (String) -> Unit,
    onEditTrackerDraft: (String) -> Unit,
    onEditTrackerSetAsSelected: (Boolean) -> Unit,
    onEditGroupDraft: (String) -> Unit,
    onSubmitCreateTracker: () -> Unit,
    onSubmitCreateGroup: () -> Unit,
    onSubmitEditTracker: () -> Unit,
    onSubmitEditGroup: () -> Unit,
) {
    when (dialog) {
        TrackersGroupsDialog.Hidden -> Unit
        is TrackersGroupsDialog.CreateTracker -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.trackers_dialog_create_tracker_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = dialog.nameDraft,
                            onValueChange = { onCreateTrackerDraft(it, dialog.colorDraft) },
                            label = { Text(stringResource(R.string.trackers_field_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = dialog.colorDraft,
                            onValueChange = { onCreateTrackerDraft(dialog.nameDraft, it) },
                            label = { Text(stringResource(R.string.trackers_field_color_optional)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = dialog.setAsSelectedTracker,
                                onCheckedChange = onCreateTrackerSetAsSelected
                            )
                            Text(text = stringResource(R.string.trackers_field_set_as_selected_tracker))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onSubmitCreateTracker) {
                        Text(stringResource(R.string.trackers_dialog_confirm_create))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.trackers_dialog_cancel))
                    }
                },
            )
        }
        is TrackersGroupsDialog.CreateGroup -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.trackers_dialog_create_group_title)) },
                text = {
                    OutlinedTextField(
                        value = dialog.nameDraft,
                        onValueChange = onCreateGroupDraft,
                        label = { Text(stringResource(R.string.trackers_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = onSubmitCreateGroup) {
                        Text(stringResource(R.string.trackers_dialog_confirm_create))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.trackers_dialog_cancel))
                    }
                },
            )
        }
        is TrackersGroupsDialog.EditTracker -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.trackers_dialog_edit_tracker_title)) },
                text = {
                    OutlinedTextField(
                        value = dialog.nameDraft,
                        onValueChange = onEditTrackerDraft,
                        label = { Text(stringResource(R.string.trackers_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = dialog.setAsSelectedTracker,
                            onCheckedChange = onEditTrackerSetAsSelected
                        )
                        Text(text = stringResource(R.string.trackers_field_set_as_selected_tracker))
                    }
                },
                confirmButton = {
                    TextButton(onClick = onSubmitEditTracker) {
                        Text(stringResource(R.string.trackers_dialog_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.trackers_dialog_cancel))
                    }
                },
            )
        }
        is TrackersGroupsDialog.EditGroup -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.trackers_dialog_edit_group_title)) },
                text = {
                    OutlinedTextField(
                        value = dialog.nameDraft,
                        onValueChange = onEditGroupDraft,
                        label = { Text(stringResource(R.string.trackers_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = onSubmitEditGroup) {
                        Text(stringResource(R.string.trackers_dialog_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.trackers_dialog_cancel))
                    }
                },
            )
        }
    }
}
