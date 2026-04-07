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
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.ui.components.GeoVaultCompactDismissTitleBar
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPullRefreshLoadingContainer
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTab
import com.geovault.common.ui.components.GeoVaultTopTabBehavior
import com.geovault.common.ui.components.GeoVaultTopTabSurface
import com.geovault.common.ui.components.GeoVaultTopTabSwipeMode
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.parseHexToColorInt
import com.geovault.tracker.presentation.GroupShareVisibility
import com.geovault.tracker.presentation.OwnershipActionPolicy
import com.geovault.tracker.presentation.TrackersGroupsDialog
import com.geovault.tracker.presentation.DiscoverOverlayMode
import com.geovault.tracker.presentation.SharedSurfaceItem
import com.geovault.tracker.presentation.SharedViewMode
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
            vm.preloadSharedSurface()
        }
    }
    LaunchedEffect(navigationRequest) {
        val request = navigationRequest ?: return@LaunchedEffect
        vm.openFromNavigationSubTab(request.subTab)
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
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
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
                onShowSharedList = vm::showSharedList,
                onShowDiscoverOverlay = vm::showDiscoverOverlay,
                onShowPublicOverlay = vm::showPublicOverlay,
                onDiscoverModeChanged = vm::setDiscoverOverlayMode,
                onDiscoverOnMapQueryChanged = vm::updateDiscoverOnMapQuery,
                onDiscoverIncomingQueryChanged = vm::updateDiscoverIncomingQuery,
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
                onUnsubscribePublicTracker = { trackerId ->
                    pendingActionKey = "public-remove-tracker-$trackerId"
                    vm.unsubscribeTracker(trackerId) { }
                },
                onUnsubscribePublicGroup = { trackIds ->
                    val key = trackIds.sorted().joinToString(separator = ",")
                    pendingActionKey = "public-remove-group-$key"
                    vm.unsubscribeAllTracksInGroup(trackIds) { }
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

@Composable
private fun ColumnScope.SharedAuthenticatedBody(
    state: SharedUiState,
    onShowSharedList: () -> Unit,
    onShowDiscoverOverlay: () -> Unit,
    onShowPublicOverlay: () -> Unit,
    onDiscoverModeChanged: (DiscoverOverlayMode) -> Unit,
    onDiscoverOnMapQueryChanged: (String) -> Unit,
    onDiscoverIncomingQueryChanged: (String) -> Unit,
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
    onUnsubscribePublicTracker: (String) -> Unit,
    onUnsubscribePublicGroup: (List<String>) -> Unit,
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
    BackHandler(enabled = state.viewMode != SharedViewMode.SHARED_LIST) {
        onShowSharedList()
    }
    var highlightedItemKey by remember { mutableStateOf<String?>(null) }
    var navigationRefreshAttempts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val context = LocalContext.current
    val selectedTrackerId = remember(state.trackers) { SelectedTrackerPrefs.selectedTrackerId(context) }
    val sharedListState = rememberLazyListState()
    val filteredSharedItems = state.filteredSections.sharedItems
    val filteredDiscoverOnMapTrackers = state.filteredSections.discoverOnMyMapTrackers
    val filteredDiscoverOnMapGroups = state.filteredSections.discoverOnMyMapGroups
    val filteredIncomingTrackers = state.filteredSections.incomingTrackers
    val filteredIncomingGroups = state.filteredSections.incomingGroups
    val filteredPublicTrackers = state.filteredSections.publicTrackers
    val filteredPublicGroups = state.filteredSections.publicGroups
    val publicTrackerAddedState = remember { mutableStateMapOf<String, Boolean>() }
    val publicGroupAddedState = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(navigationRequest, filteredSharedItems, state.isLoading) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (request.subTab != com.geovault.tracker.presentation.SharedSubTab.SHARED ||
            request.focus != MapHostNavigationFocus.SCROLL_TO_ITEM
        ) {
            onNavigationRequestHandled()
            return@LaunchedEffect
        }
        if (state.viewMode != SharedViewMode.SHARED_LIST) {
            onShowSharedList()
        }
        val (targetIndex, targetItemKey) = when {
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
                if (groupOffset >= 0) Pair(groupOffset, "s-g-${request.groupId}") else Pair(-1, null)
            }
            else -> Pair(-1, null)
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
            sharedListState.animateScrollToItem(targetIndex)
            highlightedItemKey = targetItemKey
        }
        navigationRefreshAttempts = navigationRefreshAttempts - requestKey
        onNavigationRequestHandled()
    }
    LaunchedEffect(sharedListState) {
        snapshotFlow { sharedListState.isScrollInProgress }
            .collect { inProgress ->
                if (inProgress) highlightedItemKey = null
            }
    }
    LaunchedEffect(highlightedItemKey) {
        if (highlightedItemKey.isNullOrBlank()) return@LaunchedEffect
        delay(1800)
        highlightedItemKey = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (state.viewMode) {
            SharedViewMode.SHARED_LIST -> {
                SharedMainListSurface(
                    state = state,
                    filteredSharedItems = filteredSharedItems,
                    highlightedItemKey = highlightedItemKey,
                    selectedTrackerId = selectedTrackerId,
                    listState = sharedListState,
                    onRefresh = onRefresh,
                    onEditSharedTracker = onEditSharedTracker,
                    onEditSharedGroup = onEditSharedGroup,
                    onOpenTrackerOnMap = onOpenTrackerOnMap,
                    onOpenGroupOnMap = onOpenGroupOnMap,
                    onViewTrackerParams = onViewTrackerParams,
                    onOpenGroupActions = onOpenGroupActions,
                    enabled = !state.isLoading,
                )
            }
            SharedViewMode.DISCOVER_OVERLAY -> {
                DiscoverOverlaySurface(
                    state = state,
                    filteredOnMyMapTrackers = filteredDiscoverOnMapTrackers,
                    filteredOnMyMapGroups = filteredDiscoverOnMapGroups,
                    filteredIncomingTrackers = filteredIncomingTrackers,
                    filteredIncomingGroups = filteredIncomingGroups,
                    pendingActionKey = pendingActionKey,
                    onModeChanged = onDiscoverModeChanged,
                    onOnMyMapQueryChanged = onDiscoverOnMapQueryChanged,
                    onIncomingQueryChanged = onDiscoverIncomingQueryChanged,
                    onRefresh = onRefresh,
                    onClose = onShowSharedList,
                    onAddIncomingTracker = onSubscribeIncomingTracker,
                    onLeaveIncomingGroup = { groupId, groupName -> onLeaveGroup(groupId, groupName) },
                    onAcceptIncomingGroup = onAcceptGroup,
                    onRemoveOnMapTracker = { trackerId ->
                        state.trackers.firstOrNull { it.id == trackerId }?.let(onLeaveTracker)
                    },
                    onRemoveOnMapGroup = { groupId, groupName -> onLeaveGroup(groupId, groupName) },
                )
            }
            SharedViewMode.PUBLIC_OVERLAY -> {
                PublicOverlaySurface(
                    state = state,
                    filteredPublicTrackers = filteredPublicTrackers,
                    filteredPublicGroups = filteredPublicGroups,
                    pendingActionKey = pendingActionKey,
                    onQueryChanged = onPublicQueryChanged,
                    onRefresh = onRefresh,
                    onClose = onShowSharedList,
                    publicTrackerAddedState = publicTrackerAddedState,
                    publicGroupAddedState = publicGroupAddedState,
                    onAddTracker = { trackerId ->
                        onSubscribePublicTracker(trackerId)
                        publicTrackerAddedState[trackerId] = true
                    },
                    onRemoveTracker = { trackerId ->
                        onUnsubscribePublicTracker(trackerId)
                        publicTrackerAddedState[trackerId] = false
                    },
                    onAddGroup = { trackIds ->
                        val groupKey = trackIds.sorted().joinToString(separator = ",")
                        onSubscribePublicGroup(trackIds)
                        publicGroupAddedState[groupKey] = true
                    },
                    onRemoveGroup = { trackIds ->
                        val groupKey = trackIds.sorted().joinToString(separator = ",")
                        onUnsubscribePublicGroup(trackIds)
                        publicGroupAddedState[groupKey] = false
                    },
                )
            }
        }
        if (state.viewMode == SharedViewMode.SHARED_LIST) {
            FloatingActionButton(
                onClick = onShowPublicOverlay,
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 88.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = stringResource(R.string.shared_action_open_public),
                )
            }
            FloatingActionButton(
                onClick = onShowDiscoverOverlay,
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.shared_action_open_discover),
                )
            }
        }
    }
}

@Composable
private fun SharedMainListSurface(
    state: SharedUiState,
    filteredSharedItems: List<SharedSurfaceItem>,
    highlightedItemKey: String?,
    selectedTrackerId: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRefresh: () -> Unit,
    onEditSharedTracker: (Tracker) -> Unit,
    onEditSharedGroup: (Group) -> Unit,
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit,
    onOpenGroupOnMap: (groupId: String) -> Unit,
    onViewTrackerParams: (Tracker) -> Unit,
    onOpenGroupActions: (group: Group, highlightedTrackerId: String?) -> Unit,
    enabled: Boolean,
) {
    GeoVaultPullRefreshLoadingContainer(
        refreshing = state.isLoading,
        showBlockingLoader = state.isLoading,
        onRefresh = onRefresh,
        canRefresh = !state.isLoading,
        loadingText = stringResource(R.string.loading_trackers),
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filteredSharedItems.isEmpty()) {
                item {
                    SharedEmptyState()
                }
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
                                onOpenMap = { onOpenTrackerOnMap(tracker.id, tracker.name) },
                                onViewParams = { onViewTrackerParams(tracker) },
                                onEdit = { onEditSharedTracker(tracker) },
                                isHighlighted = highlightedItemKey == "s-t-${tracker.id}",
                                isSelected = tracker.id == selectedTrackerId,
                                enabled = enabled,
                            )
                        }
                        is SharedSurfaceItem.GroupItem -> {
                            val group = item.group
                            SharedMemberGroupCard(
                                group = group,
                                onEdit = { onEditSharedGroup(group) },
                                onOpenMap = { onOpenGroupOnMap(group.id) },
                                onOpenActions = { onOpenGroupActions(group, null) },
                                isHighlighted = highlightedItemKey == "s-g-${group.id}",
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.shared_empty_title),
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colors.primary,
            )
            Text(
                text = stringResource(R.string.shared_empty_line_1),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Public,
                contentDescription = null,
                tint = MaterialTheme.colors.primary,
            )
            Text(
                text = stringResource(R.string.shared_empty_line_2),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DiscoverOverlaySurface(
    state: SharedUiState,
    filteredOnMyMapTrackers: List<AvailableToAddItem>,
    filteredOnMyMapGroups: List<AvailableToAddGroup>,
    filteredIncomingTrackers: List<AvailableToAddItem>,
    filteredIncomingGroups: List<AvailableToAddGroup>,
    pendingActionKey: String?,
    onModeChanged: (DiscoverOverlayMode) -> Unit,
    onOnMyMapQueryChanged: (String) -> Unit,
    onIncomingQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onAddIncomingTracker: (String) -> Unit,
    onLeaveIncomingGroup: (String, String) -> Unit,
    onAcceptIncomingGroup: (String) -> Unit,
    onRemoveOnMapTracker: (String) -> Unit,
    onRemoveOnMapGroup: (String, String) -> Unit,
) {
    val listState = rememberLazyListState()
    val tabs = listOf(
        GeoVaultTab(
            value = DiscoverOverlayMode.ON_MY_MAP,
            label = stringResource(R.string.shared_discover_tab_on_my_map),
        ),
        GeoVaultTab(
            value = DiscoverOverlayMode.INCOMING,
            label = stringResource(R.string.shared_discover_tab_incoming),
        ),
    )
    val loadingTrackersText = stringResource(R.string.loading_trackers)
    GeoVaultTopTabSurface(
        tabs = tabs,
        selectedTab = state.discoverMode,
        onTabSelected = onModeChanged,
        behavior = GeoVaultTopTabBehavior(
            swipeMode = GeoVaultTopTabSwipeMode.ALWAYS,
            isTabRefreshing = { state.isLoading },
            isTabBlocking = { state.isLoading },
            canRefreshTab = { !state.isLoading },
            isPullRefreshEnabled = { true },
            loadingTextForTab = { loadingTrackersText },
            onRefreshTab = { onRefresh() },
        ),
        titleForTab = {
            Column(modifier = Modifier.fillMaxWidth()) {
                GeoVaultCompactDismissTitleBar(
                    title = stringResource(R.string.shared_discover_overlay_title),
                    onClose = onClose,
                )
                Divider()
            }
        },
        headerForTab = { tab ->
            val activeQuery = if (tab == DiscoverOverlayMode.ON_MY_MAP) {
                state.discoverOnMapQuery
            } else {
                state.discoverIncomingQuery
            }
            GeoVaultInput(
                value = activeQuery,
                onValueChange = { next ->
                    if (tab == DiscoverOverlayMode.ON_MY_MAP) onOnMyMapQueryChanged(next)
                    else onIncomingQueryChanged(next)
                },
                label = stringResource(R.string.shared_search_label),
                placeholder = stringResource(R.string.shared_search_hint),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                enabled = !state.isLoading,
            )
        },
        contentForTab = { tab ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (tab == DiscoverOverlayMode.ON_MY_MAP) {
                if (filteredOnMyMapTrackers.isEmpty() && filteredOnMyMapGroups.isEmpty()) {
                    item { EmptyLine(stringResource(R.string.shared_empty_discover_on_my_map)) }
                }
                items(filteredOnMyMapTrackers, key = { "m-t-${it.id}" }) { item ->
                    DiscoverOnMapTrackerCard(
                        item = item,
                        onRemove = { onRemoveOnMapTracker(item.id) },
                        enabled = !state.isLoading,
                    )
                }
                items(filteredOnMyMapGroups, key = { "m-g-${it.id}" }) { group ->
                    DiscoverOnMapGroupCard(
                        group = group,
                        onRemove = { onRemoveOnMapGroup(group.id, group.name) },
                        enabled = !state.isLoading,
                    )
                }
            } else {
                if (filteredIncomingTrackers.isEmpty() && filteredIncomingGroups.isEmpty()) {
                    item { EmptyLine(stringResource(R.string.shared_empty_discover_incoming)) }
                }
                items(filteredIncomingTrackers, key = { "d-t-${it.id}" }) { item ->
                    DiscoverIncomingTrackerCard(
                        item = item,
                        onAdd = { onAddIncomingTracker(item.id) },
                        onRejectShare = {},
                        showRejectAction = false,
                        isPendingAdd = pendingActionKey == "incoming-tracker-${item.id}",
                        isHighlighted = false,
                        enabled = !state.isLoading,
                    )
                }
                items(filteredIncomingGroups, key = { "d-g-${it.id}" }) { group ->
                    DiscoverIncomingGroupCard(
                        group = group,
                        onAccept = { onAcceptIncomingGroup(group.id) },
                        onLeave = { onLeaveIncomingGroup(group.id, group.name) },
                        isPendingAccept = pendingActionKey == "incoming-group-${group.id}",
                        isHighlighted = false,
                        enabled = !state.isLoading,
                    )
                }
            }
        }
    })
}

@Composable
private fun PublicOverlaySurface(
    state: SharedUiState,
    filteredPublicTrackers: List<AvailableToAddItem>,
    filteredPublicGroups: List<AvailableToAddGroup>,
    pendingActionKey: String?,
    onQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    publicTrackerAddedState: Map<String, Boolean>,
    publicGroupAddedState: Map<String, Boolean>,
    onAddTracker: (String) -> Unit,
    onRemoveTracker: (String) -> Unit,
    onAddGroup: (List<String>) -> Unit,
    onRemoveGroup: (List<String>) -> Unit,
) {
    val listState = rememberLazyListState()
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            GeoVaultCompactDismissTitleBar(
                title = stringResource(R.string.shared_public_overlay_title),
                onClose = onClose,
            )
            Divider()
        }
        GeoVaultInput(
            value = state.publicQuery,
            onValueChange = onQueryChanged,
            label = stringResource(R.string.shared_search_label),
            placeholder = stringResource(R.string.shared_search_hint),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            enabled = !state.isLoading,
        )
        GeoVaultPullRefreshLoadingContainer(
            refreshing = state.isLoading,
            showBlockingLoader = state.isLoading,
            onRefresh = onRefresh,
            canRefresh = !state.isLoading,
            loadingText = stringResource(R.string.loading_trackers),
            modifier = Modifier
                .fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (filteredPublicTrackers.isEmpty() && filteredPublicGroups.isEmpty()) {
                    item { EmptyLine(stringResource(R.string.shared_empty_public_trackers)) }
                }
                items(filteredPublicTrackers, key = { "p-t-${it.id}" }) { item ->
                    PublicAddTrackerCard(
                        item = item,
                        isAdded = publicTrackerAddedState[item.id] == true,
                        onAdd = { onAddTracker(item.id) },
                        onRemove = { onRemoveTracker(item.id) },
                        isPendingAdd = pendingActionKey == "public-tracker-${item.id}",
                        isHighlighted = false,
                        enabled = !state.isLoading,
                    )
                }
                items(filteredPublicGroups, key = { "p-g-${it.id}" }) { group ->
                    val groupKey = group.track_ids.sorted().joinToString(separator = ",")
                    PublicAddGroupCard(
                        group = group,
                        isAdded = publicGroupAddedState[groupKey] == true,
                        onAddGroup = { onAddGroup(group.track_ids) },
                        onRemoveGroup = { onRemoveGroup(group.track_ids) },
                        isPendingAdd = pendingActionKey == "public-group-${group.track_ids.sorted().joinToString(separator = ",")}",
                        isHighlighted = false,
                        enabled = !state.isLoading,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverOnMapTrackerCard(
    item: AvailableToAddItem,
    onRemove: () -> Unit,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = item.name,
        ownerEmail = item.owner_email,
        iconRes = R.drawable.ic_chevron_track,
        iconTint = GeoVaultColorTokens.PrimaryBlue,
        state = TrackerAddRowActionState.ADDED_DELETE,
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = {},
        onRemove = onRemove,
    )
}

@Composable
private fun DiscoverOnMapGroupCard(
    group: AvailableToAddGroup,
    onRemove: () -> Unit,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = group.name,
        ownerEmail = group.owner_email,
        iconRes = R.drawable.ic_groups,
        iconTint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        state = TrackerAddRowActionState.ADDED_DELETE,
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = {},
        onRemove = onRemove,
    )
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
    onOpenMap: () -> Unit,
    onViewParams: () -> Unit,
    onEdit: () -> Unit,
    isHighlighted: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
) {
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
            }
        }
    }
}

@Composable
private fun SharedMemberGroupCard(
    group: Group,
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
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
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
                horizontalArrangement = Arrangement.Start,
            ) {
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_view_group_members),
                    onClick = onOpenActions,
                    enabled = enabled,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_view_group_on_map),
                    onClick = onOpenMap,
                    enabled = enabled,
                )
                GeoVaultInlineActionButton(
                    text = stringResource(R.string.trackers_action_edit),
                    onClick = onEdit,
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
    showRejectAction: Boolean = true,
    isPendingAdd: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = item.name,
        ownerEmail = item.owner_email,
        iconRes = R.drawable.ic_chevron_track,
        iconTint = GeoVaultColorTokens.PrimaryBlue,
        state = if (isPendingAdd) TrackerAddRowActionState.ADDING else TrackerAddRowActionState.IDLE,
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = onAdd,
        onRemove = onRejectShare,
    )
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
    TrackerAddRowCard(
        name = group.name,
        ownerEmail = group.owner_email,
        iconRes = R.drawable.ic_groups,
        iconTint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        state = if (isPendingAccept) TrackerAddRowActionState.ADDING else TrackerAddRowActionState.IDLE,
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = onAccept,
        onRemove = onLeave,
    )
}

@Composable
private fun PublicAddTrackerCard(
    item: AvailableToAddItem,
    isAdded: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    isPendingAdd: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = item.name,
        ownerEmail = item.owner_email,
        iconRes = R.drawable.ic_chevron_track,
        iconTint = GeoVaultColorTokens.PrimaryBlue,
        state = when {
            isPendingAdd -> TrackerAddRowActionState.ADDING
            isAdded -> TrackerAddRowActionState.ADDED_DELETE
            else -> TrackerAddRowActionState.IDLE
        },
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = onAdd,
        onRemove = onRemove,
    )
}

@Composable
private fun PublicAddGroupCard(
    group: AvailableToAddGroup,
    isAdded: Boolean,
    onAddGroup: () -> Unit,
    onRemoveGroup: () -> Unit,
    isPendingAdd: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = group.name,
        ownerEmail = group.owner_email,
        iconRes = R.drawable.ic_groups,
        iconTint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        state = when {
            isPendingAdd -> TrackerAddRowActionState.ADDING
            isAdded -> TrackerAddRowActionState.ADDED_DELETE
            else -> TrackerAddRowActionState.IDLE
        },
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled && group.track_ids.isNotEmpty(),
        onAdd = onAddGroup,
        onRemove = onRemoveGroup,
    )
}

