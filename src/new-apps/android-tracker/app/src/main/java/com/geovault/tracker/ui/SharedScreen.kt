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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Card
import androidx.compose.material.TextButton
import androidx.compose.material.AlertDialog
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.GroupShareVisibility
import com.geovault.tracker.presentation.OwnershipActionPolicy
import com.geovault.tracker.presentation.TrackersGroupsDialog
import com.geovault.tracker.presentation.SharedSurfaceItem
import com.geovault.tracker.presentation.SharedSubTab
import com.geovault.tracker.presentation.SharedUiState
import com.geovault.tracker.presentation.SharedViewModel
import com.geovault.tracker.presentation.TrackerLeaveKind
import kotlinx.coroutines.delay

@Composable
fun SharedScreen(
    isAuthenticated: Boolean,
    serverUrl: String,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    isConnecting: Boolean,
    onOpenSettings: () -> Unit,
    navigationRequest: SharedHostNavigationRequest? = null,
    onNavigationTargetConsumed: () -> Unit = {},
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit = { _, _ -> },
    onOpenGroupOnMap: (groupId: String) -> Unit = {},
    onRequestTrackerParams: (TrackerParamsUiModel) -> Unit = {},
) {
    val vm: SharedViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    var pendingConfirmAction by remember { mutableStateOf<SharedConfirmAction?>(null) }
    var pendingNavigationRequest by remember { mutableStateOf<SharedHostNavigationRequest?>(null) }
    var groupActionsDialog by remember { mutableStateOf<GroupMembersOverlayState?>(null) }
    var pendingActionKey by remember { mutableStateOf<String?>(null) }
    var snackbarModel by remember { mutableStateOf<GeoVaultSnackbarModel?>(null) }
    var editSharedTrackerDialog by remember { mutableStateOf<Tracker?>(null) }
    var editSharedGroupDialog by remember { mutableStateOf<TrackersGroupsDialog.EditGroup?>(null) }

    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            vm.refreshAll()
        }
    }
    LaunchedEffect(navigationRequest) {
        val request = navigationRequest ?: return@LaunchedEffect
        vm.setSubTab(request.subTab)
        pendingNavigationRequest = request
        onNavigationTargetConsumed()
    }
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            pendingActionKey = null
        }
    }
    LaunchedEffect(vm) {
        vm.snackbarEvents.collect { message ->
            snackbarModel = GeoVaultSnackbarModel(
                id = "shared-${message.hashCode()}-${System.currentTimeMillis()}",
                message = message,
            )
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
            if (groupActionsDialog != null) {
                val dialog = groupActionsDialog!!
                GroupActionsScreen(
                    group = dialog.group,
                    allTrackers = state.trackers,
                    highlightedTrackerId = dialog.highlightedTrackerId,
                    onDismiss = { groupActionsDialog = null },
                    onViewTrackerOnMap = { trackerId ->
                        groupActionsDialog = null
                        onOpenTrackerOnMap(trackerId, null)
                    },
                    onViewTrackerParams = { tracker ->
                        tracker.toTrackerParamsUiModelOrNull()?.let(onRequestTrackerParams)
                    },
                    onViewTrackerInList = null,
                    onEditGroup = { _ -> },
                    onViewGroupOnMap = { groupId ->
                        groupActionsDialog = null
                        onOpenGroupOnMap(groupId)
                    },
                )
            } else if (editSharedGroupDialog != null) {
                GroupEditScreen(
                    dialog = editSharedGroupDialog!!,
                    allTrackers = state.trackers,
                    shareRecipientUsers = emptyList(),
                    isShareRecipientSuggestionsLoading = false,
                    isPickerRefreshing = false,
                    isSaving = state.isLoading,
                    onDismiss = { editSharedGroupDialog = null },
                    onReloadShareRecipients = {},
                    onRefreshTrackers = {},
                    onNameDraftChanged = {},
                    onVisibilityChanged = {},
                    onToggleSharedEmail = {},
                    onWorldShareToggled = {},
                    onHiddenChanged = {},
                    onUpdateDraftTrackers = {},
                    onDeleteGroup = {},
                    onLeaveGroup = {
                        val groupId = editSharedGroupDialog!!.group.id
                        editSharedGroupDialog = null
                        vm.leaveGroup(groupId)
                    },
                    onSave = {},
                )
            } else {
            SharedAuthenticatedBody(
                state = state,
                onSubTabSelected = vm::setSubTab,
                onSharedQueryChanged = vm::updateSharedQuery,
                onDiscoverQueryChanged = vm::updateDiscoverQuery,
                onPublicQueryChanged = vm::updatePublicQuery,
                onRefresh = vm::refreshAll,
                onToggleTrackerMapHidden = vm::toggleTrackerHiddenOnMap,
                onToggleGroupMapHidden = vm::toggleGroupHiddenOnMap,
                onLeaveTracker = { tracker ->
                    pendingConfirmAction = SharedConfirmAction.LeaveTracker(tracker)
                },
                onLeaveGroup = { groupId, groupName ->
                    pendingConfirmAction = SharedConfirmAction.LeaveGroup(
                        groupId = groupId,
                        groupName = groupName
                    )
                },
                onAcceptGroup = { groupId ->
                    pendingActionKey = "incoming-group-$groupId"
                    vm.acceptGroupShare(groupId)
                },
                onSubscribeIncomingTracker = { trackerId ->
                    pendingActionKey = "incoming-tracker-$trackerId"
                    vm.subscribeIncomingTracker(trackerId)
                },
                onLeaveIncomingShare = { trackerId, trackerName ->
                    pendingConfirmAction = SharedConfirmAction.RejectIncomingShare(
                        trackerId = trackerId,
                        trackerName = trackerName
                    )
                },
                onSubscribePublicTracker = { trackerId ->
                    pendingActionKey = "public-tracker-$trackerId"
                    vm.subscribePublicTracker(trackerId)
                },
                onSubscribePublicGroup = { trackIds ->
                    val key = trackIds.sorted().joinToString(separator = ",")
                    pendingActionKey = "public-group-$key"
                    vm.subscribePublicGroup(trackIds)
                },
                onUnsubscribeAllInGroup = { groupName, trackIds ->
                    pendingConfirmAction = SharedConfirmAction.UnsubscribeAllInGroup(
                        groupName = groupName,
                        trackIds = trackIds
                    )
                },
                onEditSharedTracker = { tracker ->
                    editSharedTrackerDialog = tracker
                },
                onEditSharedGroup = { group ->
                    editSharedGroupDialog = TrackersGroupsDialog.EditGroup(
                        group = group,
                        nameDraft = group.name,
                        visibilityDraft = GroupShareVisibility.fromApiValue(group.visibility),
                        sharedEmailsDraft = group.shared_with_emails.orEmpty().joinToString(", "),
                        worldShareEnabledDraft = !group.world_share_id.isNullOrBlank() ||
                            !group.world_share_url.isNullOrBlank(),
                    )
                },
                navigationRequest = pendingNavigationRequest,
                onNavigationRequestHandled = { pendingNavigationRequest = null },
                onOpenTrackerOnMap = onOpenTrackerOnMap,
                onOpenGroupOnMap = onOpenGroupOnMap,
                onViewTrackerParams = { tracker ->
                    tracker.toTrackerParamsUiModelOrNull()?.let(onRequestTrackerParams)
                },
                onOpenGroupActions = { group, highlightedTrackerId ->
                    groupActionsDialog = GroupMembersOverlayState(
                        group = group,
                        highlightedTrackerId = highlightedTrackerId,
                    )
                },
                pendingActionKey = pendingActionKey,
            )
            }
        },
    )
    GeoVaultSnackbarHost(
        model = snackbarModel,
        onDismiss = { snackbarModel = null },
        onAction = { },
    )

    SharedActionConfirmDialog(
        pendingAction = pendingConfirmAction,
        onDismiss = { pendingConfirmAction = null },
        onConfirm = { action ->
            when (action) {
                is SharedConfirmAction.LeaveTracker -> vm.leaveTracker(action.tracker)
                is SharedConfirmAction.LeaveGroup -> vm.leaveGroup(action.groupId)
                is SharedConfirmAction.RejectIncomingShare -> vm.leaveIncomingShare(action.trackerId)
                is SharedConfirmAction.UnsubscribeAllInGroup -> vm.unsubscribeAllTracksInGroup(action.trackIds)
            }
            pendingConfirmAction = null
        }
    )
    editSharedTrackerDialog?.let { tracker ->
        SharedTrackerActionsDialog(
            tracker = tracker,
            onDismiss = { editSharedTrackerDialog = null },
            onUnsubscribe = {
                vm.unsubscribeTracker(tracker.id)
                editSharedTrackerDialog = null
            },
            onRemoveFromShare = {
                vm.leaveTrackerShare(tracker.id)
                editSharedTrackerDialog = null
            },
        )
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun ColumnScope.SharedAuthenticatedBody(
    state: SharedUiState,
    onSubTabSelected: (SharedSubTab) -> Unit,
    onSharedQueryChanged: (String) -> Unit,
    onDiscoverQueryChanged: (String) -> Unit,
    onPublicQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleTrackerMapHidden: (String) -> Unit,
    onToggleGroupMapHidden: (String) -> Unit,
    onLeaveTracker: (Tracker) -> Unit,
    onLeaveGroup: (String, String) -> Unit,
    onAcceptGroup: (String) -> Unit,
    onSubscribeIncomingTracker: (String) -> Unit,
    onLeaveIncomingShare: (String, String) -> Unit,
    onSubscribePublicTracker: (String) -> Unit,
    onSubscribePublicGroup: (List<String>) -> Unit,
    onUnsubscribeAllInGroup: (String, List<String>) -> Unit,
    onEditSharedTracker: (Tracker) -> Unit,
    onEditSharedGroup: (Group) -> Unit,
    navigationRequest: SharedHostNavigationRequest?,
    onNavigationRequestHandled: () -> Unit,
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit,
    onOpenGroupOnMap: (groupId: String) -> Unit,
    onViewTrackerParams: (Tracker) -> Unit,
    onOpenGroupActions: (group: Group, highlightedTrackerId: String?) -> Unit,
    pendingActionKey: String?,
) {
    var highlightedItemKey by remember { mutableStateOf<String?>(null) }
    var navigationRefreshAttempts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val context = LocalContext.current
    val selectedTrackerId = remember(state.trackers) { SelectedTrackerPrefs.selectedTrackerId(context) }
    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.isLoading,
        onRefresh = {
            if (!state.isLoading) onRefresh()
        },
    )
    LaunchedEffect(navigationRequest) {
        val request = navigationRequest ?: return@LaunchedEffect
        when (request.subTab) {
            SharedSubTab.SHARED -> onSharedQueryChanged("")
            SharedSubTab.DISCOVER -> onDiscoverQueryChanged("")
            SharedSubTab.PUBLIC -> onPublicQueryChanged("")
        }
    }
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

        val activeQuery = when (state.subTab) {
            SharedSubTab.SHARED -> state.sharedQuery
            SharedSubTab.DISCOVER -> state.discoverQuery
            SharedSubTab.PUBLIC -> state.publicQuery
        }
        GeoVaultInput(
            value = activeQuery,
            onValueChange = { next ->
                when (state.subTab) {
                    SharedSubTab.SHARED -> onSharedQueryChanged(next)
                    SharedSubTab.DISCOVER -> onDiscoverQueryChanged(next)
                    SharedSubTab.PUBLIC -> onPublicQueryChanged(next)
                }
            },
            label = stringResource(R.string.shared_search_label),
            placeholder = stringResource(R.string.shared_search_hint),
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GeoVaultSecondaryButton(
                text = stringResource(R.string.trackers_action_refresh),
                onClick = onRefresh,
                enabled = !state.isLoading,
            )
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
                GeoVaultLoadingSpinner()
            }
            return@Column
        }

        val filteredSharedItems = state.filteredSections.sharedItems
        val filteredIncomingTrackers = state.filteredSections.incomingTrackers
        val filteredIncomingGroups = state.filteredSections.incomingGroups
        val filteredPublicTrackers = state.filteredSections.publicTrackers
        val filteredPublicGroups = state.filteredSections.publicGroups
        LaunchedEffect(
            navigationRequest,
            state.subTab,
            filteredSharedItems,
            filteredIncomingTrackers,
            filteredIncomingGroups,
            filteredPublicTrackers,
            filteredPublicGroups,
            state.isLoading,
        ) {
            val request = navigationRequest ?: return@LaunchedEffect
            if (request.subTab != state.subTab || request.focus != MapHostNavigationFocus.SCROLL_TO_ITEM) {
                if (request.subTab == state.subTab) {
                    onNavigationRequestHandled()
                }
                return@LaunchedEffect
            }
            fun firstSectionBlockSize(itemCount: Int): Int {
                // Header + either N rows or a single empty-state row.
                return if (itemCount > 0) 1 + itemCount else 2
            }
            val (targetIndex, targetItemKey) = when (state.subTab) {
                SharedSubTab.SHARED -> {
                    when {
                        !request.trackerId.isNullOrBlank() -> {
                            val trackerOffset = filteredSharedItems.indexOfFirst { item ->
                                item is SharedSurfaceItem.TrackerItem && item.tracker.id == request.trackerId
                            }
                            if (trackerOffset >= 0) {
                                Pair(trackerOffset, "s-t-${request.trackerId}")
                            } else {
                                val groupOffset = filteredSharedItems.indexOfFirst { item ->
                                    item is SharedSurfaceItem.GroupItem &&
                                        item.group.track_ids.orEmpty().any { it.trim() == request.trackerId }
                                }
                                if (groupOffset >= 0) {
                                    val groupId = (filteredSharedItems[groupOffset] as SharedSurfaceItem.GroupItem).group.id
                                    Pair(groupOffset, "s-g-$groupId")
                                } else {
                                    Pair(-1, null)
                                }
                            }
                        }
                        !request.groupId.isNullOrBlank() -> {
                            val groupOffset = filteredSharedItems.indexOfFirst { item ->
                                item is SharedSurfaceItem.GroupItem && item.group.id == request.groupId
                            }
                            if (groupOffset >= 0) {
                                Pair(groupOffset, "s-g-${request.groupId}")
                            } else {
                                Pair(-1, null)
                            }
                        }
                        else -> Pair(-1, null)
                    }
                }
                SharedSubTab.DISCOVER -> {
                    when {
                        !request.trackerId.isNullOrBlank() -> {
                            val trackerOffset = filteredIncomingTrackers.indexOfFirst { it.id == request.trackerId }
                            if (trackerOffset >= 0) {
                                Pair(1 + trackerOffset, "d-t-${request.trackerId}")
                            } else {
                                Pair(-1, null)
                            }
                        }
                        !request.groupId.isNullOrBlank() -> {
                            val groupOffset = filteredIncomingGroups.indexOfFirst { it.id == request.groupId }
                            if (groupOffset >= 0) {
                                val start = firstSectionBlockSize(filteredIncomingTrackers.size) + 1
                                Pair(start + groupOffset, "d-g-${request.groupId}")
                            } else {
                                Pair(-1, null)
                            }
                        }
                        else -> Pair(-1, null)
                    }
                }
                SharedSubTab.PUBLIC -> {
                    when {
                        !request.trackerId.isNullOrBlank() -> {
                            val trackerOffset = filteredPublicTrackers.indexOfFirst { it.id == request.trackerId }
                            if (trackerOffset >= 0) {
                                Pair(1 + trackerOffset, "p-t-${request.trackerId}")
                            } else {
                                Pair(-1, null)
                            }
                        }
                        !request.groupId.isNullOrBlank() -> {
                            val groupOffset = filteredPublicGroups.indexOfFirst { it.id == request.groupId }
                            if (groupOffset >= 0) {
                                val start = firstSectionBlockSize(filteredPublicTrackers.size) + 1
                                Pair(start + groupOffset, "p-g-${request.groupId}")
                            } else {
                                Pair(-1, null)
                            }
                        }
                        else -> Pair(-1, null)
                    }
                }
            }
            val requestKey = request.toNavigationKey()
            if (targetIndex < 0 && (request.trackerId != null || request.groupId != null)) {
                val attempts = navigationRefreshAttempts[requestKey] ?: 0
                if (attempts == 0 && !state.isLoading) {
                    navigationRefreshAttempts = navigationRefreshAttempts + (requestKey to 1)
                    onRefresh()
                    return@LaunchedEffect
                }
            }
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
                highlightedItemKey = targetItemKey
            }
            navigationRefreshAttempts = navigationRefreshAttempts - requestKey
            onNavigationRequestHandled()
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }
                .collect { inProgress ->
                    if (inProgress) highlightedItemKey = null
                }
        }
        LaunchedEffect(highlightedItemKey) {
            if (highlightedItemKey.isNullOrBlank()) return@LaunchedEffect
            delay(1800)
            highlightedItemKey = null
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pullRefresh(pullRefreshState),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.subTab) {
                    SharedSubTab.SHARED -> {
                        if (filteredSharedItems.isEmpty()) {
                            item { EmptyLine(stringResource(R.string.shared_empty_shared_trackers)) }
                        } else {
                            items(
                                items = filteredSharedItems,
                                key = { item ->
                                    when (item) {
                                        is SharedSurfaceItem.TrackerItem -> "s-t-${item.tracker.id}"
                                        is SharedSurfaceItem.GroupItem -> "s-g-${item.group.id}"
                                    }
                                }
                            ) { item ->
                                when (item) {
                                    is SharedSurfaceItem.TrackerItem -> {
                                        val tracker = item.tracker
                                        SharedTrackerCard(
                                            tracker = tracker,
                                            hiddenOnMap = state.mapVisibility?.hidden_track_ids?.contains(tracker.id) == true,
                                            onToggleMapHidden = { onToggleTrackerMapHidden(tracker.id) },
                                            onLeave = { onLeaveTracker(tracker) },
                                            onOpenMap = { onOpenTrackerOnMap(tracker.id, tracker.name) },
                                            onViewParams = { onViewTrackerParams(tracker) },
                                            onEdit = { onEditSharedTracker(tracker) },
                                            isHighlighted = highlightedItemKey == "s-t-${tracker.id}",
                                            isSelected = tracker.id == selectedTrackerId,
                                            enabled = !state.isLoading,
                                        )
                                    }
                                    is SharedSurfaceItem.GroupItem -> {
                                        val group = item.group
                                        SharedMemberGroupCard(
                                            group = group,
                                            hiddenOnMap = state.mapVisibility?.hidden_group_ids?.contains(group.id) == true,
                                            onToggleMapHidden = { onToggleGroupMapHidden(group.id) },
                                            onLeave = { onLeaveGroup(group.id, group.name) },
                                            onUnsubscribeAll = {
                                                onUnsubscribeAllInGroup(group.name, group.track_ids.orEmpty())
                                            },
                                            onEdit = { onEditSharedGroup(group) },
                                            onOpenMap = { onOpenGroupOnMap(group.id) },
                                            onOpenActions = { onOpenGroupActions(group, null) },
                                            isHighlighted = highlightedItemKey == "s-g-${group.id}",
                                            enabled = !state.isLoading,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    SharedSubTab.DISCOVER -> {
                        item {
                            SectionTitle(stringResource(R.string.shared_section_incoming_trackers))
                        }
                        if (filteredIncomingTrackers.isEmpty()) {
                            item { EmptyLine(stringResource(R.string.shared_empty_incoming_trackers)) }
                        } else {
                            items(filteredIncomingTrackers, key = { "d-t-${it.id}" }) { item ->
                                DiscoverIncomingTrackerCard(
                                    item = item,
                                    onAdd = { onSubscribeIncomingTracker(item.id) },
                                    onRejectShare = { onLeaveIncomingShare(item.id, item.name) },
                                    isPendingAdd = pendingActionKey == "incoming-tracker-${item.id}",
                                    isHighlighted = highlightedItemKey == "d-t-${item.id}",
                                    enabled = !state.isLoading,
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            SectionTitle(stringResource(R.string.shared_section_incoming_groups))
                        }
                        if (filteredIncomingGroups.isEmpty()) {
                            item { EmptyLine(stringResource(R.string.shared_empty_incoming_groups)) }
                        } else {
                            items(filteredIncomingGroups, key = { "d-g-${it.id}" }) { group ->
                                DiscoverIncomingGroupCard(
                                    group = group,
                                    onAccept = { onAcceptGroup(group.id) },
                                    onLeave = { onLeaveGroup(group.id, group.name) },
                                    isPendingAccept = pendingActionKey == "incoming-group-${group.id}",
                                    isHighlighted = highlightedItemKey == "d-g-${group.id}",
                                    enabled = !state.isLoading,
                                )
                            }
                        }
                    }
                    SharedSubTab.PUBLIC -> {
                        item {
                            SectionTitle(stringResource(R.string.shared_section_public_trackers))
                        }
                        if (filteredPublicTrackers.isEmpty()) {
                            item { EmptyLine(stringResource(R.string.shared_empty_public_trackers)) }
                        } else {
                            items(filteredPublicTrackers, key = { "p-t-${it.id}" }) { item ->
                                PublicAddTrackerCard(
                                    item = item,
                                    onAdd = { onSubscribePublicTracker(item.id) },
                                    isPendingAdd = pendingActionKey == "public-tracker-${item.id}",
                                    isHighlighted = highlightedItemKey == "p-t-${item.id}",
                                    enabled = !state.isLoading,
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            SectionTitle(stringResource(R.string.shared_section_public_groups))
                        }
                        if (filteredPublicGroups.isEmpty()) {
                            item { EmptyLine(stringResource(R.string.shared_empty_public_groups)) }
                        } else {
                            items(filteredPublicGroups, key = { "p-g-${it.id}" }) { group ->
                                PublicAddGroupCard(
                                    group = group,
                                    onAddGroup = { onSubscribePublicGroup(group.track_ids) },
                                    isPendingAdd = pendingActionKey == "public-group-${group.track_ids.sorted().joinToString(separator = ",")}",
                                    isHighlighted = highlightedItemKey == "p-g-${group.id}",
                                    enabled = !state.isLoading,
                                )
                            }
                        }
                    }
                }
            }
            PullRefreshIndicator(
                refreshing = state.isLoading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

private sealed interface SharedConfirmAction {
    data class LeaveTracker(val tracker: Tracker) : SharedConfirmAction
    data class LeaveGroup(val groupId: String, val groupName: String) : SharedConfirmAction
    data class RejectIncomingShare(val trackerId: String, val trackerName: String) : SharedConfirmAction
    data class UnsubscribeAllInGroup(val groupName: String, val trackIds: List<String>) : SharedConfirmAction
}

@Composable
private fun SharedActionConfirmDialog(
    pendingAction: SharedConfirmAction?,
    onDismiss: () -> Unit,
    onConfirm: (SharedConfirmAction) -> Unit,
) {
    val action = pendingAction ?: return
    val title: String
    val message: String
    val confirmLabel: String
    when (action) {
        is SharedConfirmAction.LeaveTracker -> {
            val leaveKind = OwnershipActionPolicy.trackerLeaveKind(action.tracker)
            if (leaveKind == TrackerLeaveKind.LeaveShare) {
                title = stringResource(R.string.confirm_leave_share_title)
                message = stringResource(R.string.confirm_leave_share_message, action.tracker.name)
                confirmLabel = stringResource(R.string.trackers_action_leave_share)
            } else {
                title = stringResource(R.string.confirm_unsubscribe_title)
                message = stringResource(R.string.confirm_unsubscribe_message, action.tracker.name)
                confirmLabel = stringResource(R.string.trackers_action_unsubscribe)
            }
        }
        is SharedConfirmAction.LeaveGroup -> {
            title = stringResource(R.string.confirm_leave_group_title)
            message = stringResource(R.string.confirm_leave_group_message, action.groupName)
            confirmLabel = stringResource(R.string.trackers_action_leave_group)
        }
        is SharedConfirmAction.RejectIncomingShare -> {
            title = stringResource(R.string.confirm_reject_share_title)
            message = stringResource(R.string.confirm_reject_share_message, action.trackerName)
            confirmLabel = stringResource(R.string.shared_action_reject_share)
        }
        is SharedConfirmAction.UnsubscribeAllInGroup -> {
            title = stringResource(R.string.confirm_unsubscribe_all_group_title)
            message = stringResource(
                R.string.confirm_unsubscribe_all_group_message,
                action.groupName,
                action.trackIds.size
            )
            confirmLabel = stringResource(R.string.shared_action_unsubscribe_all_tracks)
        }
    }
    GeoVaultConfirmationDialog(
        title = title,
        message = message,
        onConfirm = { onConfirm(action) },
        onCancel = onDismiss,
        confirmText = confirmLabel,
        cancelText = stringResource(R.string.trackers_dialog_cancel),
    )
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

private fun SharedHostNavigationRequest.toNavigationKey(): String {
    return "${subTab.name}|${focus.name}|${trackerId.orEmpty()}|${groupId.orEmpty()}"
}

@Composable
private fun SharedTrackerCard(
    tracker: Tracker,
    hiddenOnMap: Boolean,
    onToggleMapHidden: () -> Unit,
    onLeave: () -> Unit,
    onOpenMap: () -> Unit,
    onViewParams: () -> Unit,
    onEdit: () -> Unit,
    isHighlighted: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
) {
    val leaveKind = OwnershipActionPolicy.trackerLeaveKind(tracker)
    val hasLastPosition = tracker.last_point?.size?.let { it >= 2 } == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 0.dp,
        backgroundColor = if (isHighlighted) {
            MaterialTheme.colors.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = tracker.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colors.primary,
                )
            }
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
                GeoVaultInlineActionButton(
                    text = if (hiddenOnMap) {
                        stringResource(R.string.trackers_action_show_on_map)
                    } else {
                        stringResource(R.string.trackers_action_hide_on_map)
                    },
                    onClick = onToggleMapHidden,
                    enabled = enabled,
                )
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_view_on_map),
                    onClick = onOpenMap,
                    enabled = enabled && hasLastPosition,
                )
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.map_action_view_params),
                    onClick = onViewParams,
                    enabled = enabled,
                )
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_edit),
                    onClick = onEdit,
                    enabled = enabled,
                )
                if (leaveKind != null) {
                    GeoVaultInlineActionButton(
                        text = when (leaveKind) {
                            TrackerLeaveKind.Unsubscribe -> stringResource(R.string.trackers_action_unsubscribe)
                            TrackerLeaveKind.LeaveShare -> stringResource(R.string.trackers_action_leave_share)
                        },
                        onClick = onLeave,
                        enabled = enabled,
                    )
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
    onEdit: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenActions: () -> Unit,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 0.dp,
        backgroundColor = if (isHighlighted) {
            MaterialTheme.colors.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = group.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
            val trackerCount = group.track_ids.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .size
            val subtitle = listOfNotNull(
                group.visibility,
                group.owner_email?.let { stringResource(R.string.trackers_meta_owner_line, it) },
                stringResource(R.string.trackers_meta_tracks_count, trackerCount),
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
                GeoVaultInlineActionButton(
                    text = if (hiddenOnMap) {
                        stringResource(R.string.trackers_action_show_on_map)
                    } else {
                        stringResource(R.string.trackers_action_hide_on_map)
                    },
                    onClick = onToggleMapHidden,
                    enabled = enabled,
                )
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_view_group_on_map),
                    onClick = onOpenMap,
                    enabled = enabled,
                )
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_view_group_members),
                    onClick = onOpenActions,
                    enabled = enabled,
                )
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_edit),
                    onClick = onEdit,
                    enabled = enabled,
                )
                if (!group.track_ids.isNullOrEmpty()) {
                    GeoVaultInlineActionButton(
                        text = stringResource(R.string.shared_action_unsubscribe_all_tracks),
                        onClick = onUnsubscribeAll,
                        enabled = enabled,
                    )
                }
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_leave_group),
                    onClick = onLeave,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun SharedTrackerActionsDialog(
    tracker: Tracker,
    onDismiss: () -> Unit,
    onUnsubscribe: () -> Unit,
    onRemoveFromShare: () -> Unit,
) {
    val canUnsubscribe = OwnershipActionPolicy.trackerLeaveKind(tracker) == TrackerLeaveKind.Unsubscribe
    val canRemoveFromShare = tracker.visibility == "shared" || OwnershipActionPolicy.trackerLeaveKind(tracker) == TrackerLeaveKind.LeaveShare
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = tracker.name) },
        text = {
            Text(
                text = tracker.owner_email
                    ?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.trackers_badge_not_owner),
                style = MaterialTheme.typography.body2,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                if (canUnsubscribe) {
                    TextButton(onClick = onUnsubscribe) {
                        Text(text = stringResource(R.string.trackers_action_unsubscribe))
                    }
                }
                if (canRemoveFromShare) {
                    TextButton(onClick = onRemoveFromShare) {
                        Text(text = stringResource(R.string.trackers_action_leave_share))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.trackers_dialog_cancel))
            }
        },
    )
}

@Composable
private fun DiscoverIncomingTrackerCard(
    item: AvailableToAddItem,
    onAdd: () -> Unit,
    onRejectShare: () -> Unit,
    isPendingAdd: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 0.dp,
        backgroundColor = if (isHighlighted) {
            MaterialTheme.colors.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
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
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.shared_action_reject_share),
                    onClick = onRejectShare,
                    enabled = enabled,
                )
                Spacer(modifier = Modifier.width(8.dp))
                GeoVaultInlineActionButton(
                    text = if (isPendingAdd) {
                        stringResource(R.string.shared_action_adding)
                    } else {
                        stringResource(R.string.shared_action_add_to_trackers)
                    },
                    onClick = onAdd,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun DiscoverIncomingGroupCard(
    group: AvailableToAddGroup,
    onAccept: () -> Unit,
    onLeave: () -> Unit,
    isPendingAccept: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 0.dp,
        backgroundColor = if (isHighlighted) {
            MaterialTheme.colors.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
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
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_leave_group),
                    onClick = onLeave,
                    enabled = enabled,
                )
                GeoVaultInlineActionButton(
                    text = if (isPendingAccept) {
                        stringResource(R.string.shared_action_accepting)
                    } else {
                        stringResource(R.string.trackers_action_accept_invite)
                    },
                    onClick = onAccept,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun PublicAddTrackerCard(
    item: AvailableToAddItem,
    onAdd: () -> Unit,
    isPendingAdd: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 0.dp,
        backgroundColor = if (isHighlighted) {
            MaterialTheme.colors.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
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
                GeoVaultInlineActionButton(
                    text = if (isPendingAdd) {
                        stringResource(R.string.shared_action_adding)
                    } else {
                        stringResource(R.string.shared_action_add_to_trackers)
                    },
                    onClick = onAdd,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun PublicAddGroupCard(
    group: AvailableToAddGroup,
    onAddGroup: () -> Unit,
    isPendingAdd: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 0.dp,
        backgroundColor = if (isHighlighted) {
            MaterialTheme.colors.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
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
                GeoVaultInlineActionButton(
                    text = if (isPendingAdd) {
                        stringResource(R.string.shared_action_adding)
                    } else {
                        stringResource(R.string.shared_action_add_group)
                    },
                    onClick = onAddGroup,
                    enabled = enabled && group.track_ids.isNotEmpty(),
                )
            }
        }
    }
}
