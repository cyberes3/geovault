package com.geovault.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.geo.CoordinateFormat
import com.geovault.common.ui.components.GeoVaultAddRemoveRowFlags
import com.geovault.common.ui.components.GeoVaultEmptyState
import com.geovault.common.ui.components.GeoVaultFloatingActionButtonWithTooltip
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultPullRefreshLoadingContainer
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSearchField
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.components.GeoVaultTab
import com.geovault.common.ui.components.GeoVaultTabBar
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.time.GeoVaultDateTimeFormat
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Group
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.params.toTrackerParamsRouteArgs
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.OwnershipActionPolicy
import com.geovault.tracker.presentation.DiscoverOverlayMode
import com.geovault.tracker.presentation.SharedEditActionPolicy
import com.geovault.tracker.presentation.SharedAddRemoveOperation
import com.geovault.tracker.presentation.SharedListRowModel
import com.geovault.tracker.presentation.SharedSubTab
import com.geovault.tracker.presentation.SharedViewMode
import com.geovault.tracker.presentation.SharedUiState
import com.geovault.tracker.presentation.SharedViewModel
import com.geovault.tracker.presentation.TrackerAddRemoveKeyPolicy
import com.geovault.tracker.presentation.TrackerLeaveKind
import com.geovault.tracker.ui.components.GroupItemCard
import com.geovault.tracker.ui.components.GroupItemCardModel
import com.geovault.tracker.ui.components.TrackerItemCard
import com.geovault.tracker.ui.components.TrackerItemCardModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SharedScreen(
    vm: SharedViewModel,
    sharedTabBottomNavStamp: Int = 0,
    auth: GeoVaultAuthShellState,
    navigationRequest: SharedHostNavigationRequest? = null,
    onNavigationTargetConsumed: () -> Unit = {},
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit = { _, _ -> },
    onOpenGroupOnMap: (groupId: String) -> Unit = {},
    onRequestTrackerParams: (TrackerParamsRouteArgs) -> Unit = {},
) {
    val state by vm.uiState.collectAsState()
    DisposableEffect(vm) {
        onDispose { vm.showSharedList() }
    }
    var pendingConfirmAction by remember { mutableStateOf<SharedConfirmAction?>(null) }
    var pendingNavigationRequest by remember { mutableStateOf<SharedHostNavigationRequest?>(null) }
    var groupActionsDialog by remember { mutableStateOf<GroupMembersOverlayState?>(null) }
    LaunchedEffect(sharedTabBottomNavStamp) {
        if (sharedTabBottomNavStamp == 0) return@LaunchedEffect
        groupActionsDialog = null
    }
    var snackbarModel by remember { mutableStateOf<GeoVaultSnackbarModel?>(null) }
    var editSharedTracker by remember { mutableStateOf<Tracker?>(null) }
    var editSharedGroup by remember { mutableStateOf<Group?>(null) }

    LaunchedEffect(navigationRequest) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (request.subTab == SharedSubTab.SHARED) {
            vm.clearSharedListQuery()
        }
        vm.openFromNavigationSubTab(request.subTab)
        pendingNavigationRequest = request
        onNavigationTargetConsumed()
    }
    LaunchedEffect(vm) {
        coroutineScope {
            launch {
                vm.snackbarEvents.collect { message ->
                    snackbarModel = GeoVaultSnackbarModel(
                        id = "shared-${message.hashCode()}-${System.currentTimeMillis()}",
                        message = message,
                    )
                }
            }
            launch {
                vm.dismissSharedTrackerEditId.collect { id ->
                    if (editSharedTracker?.id == id) {
                        editSharedTracker = null
                    }
                }
            }
            launch {
                vm.dismissSharedGroupEditId.collect { id ->
                    if (editSharedGroup?.id == id) {
                        editSharedGroup = null
                    }
                }
            }
        }
    }
    LaunchedEffect(editSharedTracker, state.trackers, state.pendingRemoveActionKeys) {
        val tracker = editSharedTracker ?: return@LaunchedEffect
        val stillExists = state.trackers.any { it.id == tracker.id }
        val hasPendingRemoval = state.pendingRemoveActionKeys.contains(vm.editTrackerUnsubscribePendingKey(tracker.id)) ||
            state.pendingRemoveActionKeys.contains(vm.editTrackerLeaveSharePendingKey(tracker.id))
        if (!stillExists && !hasPendingRemoval) {
            editSharedTracker = null
        }
    }
    LaunchedEffect(editSharedGroup, state.groups, state.pendingRemoveActionKeys) {
        val group = editSharedGroup ?: return@LaunchedEffect
        val stillExists = state.groups.any { it.id == group.id }
        val hasPendingRemoval = state.pendingRemoveActionKeys.contains(vm.editGroupLeavePendingKey(group.id))
        if (!stillExists && !hasPendingRemoval) {
            editSharedGroup = null
        }
    }

    // Editor sub-views (SharedTracker/Group/GroupActions) sit on top of the body in
    // [tabOverlay]. Each renders through [GeoVaultSubViewScaffold] (compact "<-Title  X"
    // dismiss strip), so the outer NavTabShell title bar stays in place across open/close.
    // Settings remains available and uses the common host-inactive path to dismiss sub-views.
    GeoVaultTabShell(
        title = stringResource(R.string.shared_screen_title),
        auth = auth,
        placeholderText = stringResource(R.string.shared_placeholder_signed_out),
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        settingsMenuEnabled = true,
        authenticatedMainContent = {
            // Discover/Public overlays render *inside* the body — they wrap themselves in
            // [GeoVaultSubViewScaffold] (compact dismiss strip), so the outer NavTabShell
            // title bar stays in place across SHARED_LIST → DISCOVER → PUBLIC transitions.
            SharedAuthenticatedBody(
                state = state,
                onShowSharedList = vm::showSharedList,
                onShowDiscoverOverlay = vm::showDiscoverOverlay,
                onShowPublicOverlay = vm::showPublicOverlay,
                onDiscoverModeChanged = vm::setDiscoverOverlayMode,
                onDiscoverOnMapQueryChanged = vm::updateDiscoverOnMapQuery,
                onDiscoverIncomingQueryChanged = vm::updateDiscoverIncomingQuery,
                onSharedListQueryChanged = vm::updateSharedListQuery,
                onPublicQueryChanged = vm::updatePublicQuery,
                onRefresh = vm::refreshAll,
                onLeaveTracker = { tracker ->
                    pendingConfirmAction = SharedConfirmAction.LeaveTracker(tracker)
                },
                onLeaveGroup = { groupId, groupName ->
                    pendingConfirmAction = SharedConfirmAction.LeaveGroup(
                        groupId = groupId,
                        groupName = groupName
                    )
                },
                onAcceptGroup = vm::requestIncomingGroupAccept,
                onSubscribeIncomingTracker = vm::requestIncomingTrackerAdd,
                onLeaveIncomingShare = { trackerId, trackerName ->
                    pendingConfirmAction = SharedConfirmAction.RejectIncomingShare(
                        trackerId = trackerId,
                        trackerName = trackerName
                    )
                },
                onRemoveOnMapTracker = { trackerId, trackerName ->
                    pendingConfirmAction = SharedConfirmAction.RemoveDiscoverOnMapTracker(
                        trackerId = trackerId,
                        trackerName = trackerName,
                    )
                },
                onRemoveOnMapGroup = vm::requestDiscoverOnMapGroupRemove,
                onSubscribePublicTracker = vm::requestPublicTrackerAdd,
                onSubscribePublicGroup = vm::requestPublicGroupAdd,
                onUnsubscribePublicTracker = vm::requestPublicTrackerRemove,
                onUnsubscribePublicGroup = vm::requestPublicGroupRemove,
                onUnsubscribeAllInGroup = { groupName, trackIds ->
                    pendingConfirmAction = SharedConfirmAction.UnsubscribeAllInGroup(
                        groupName = groupName,
                        trackIds = trackIds
                    )
                },
                onEditSharedTracker = { tracker ->
                    editSharedTracker = tracker
                },
                onEditSharedGroup = { group ->
                    if (SharedEditActionPolicy.canOpenSharedGroupEdit(group)) {
                        editSharedGroup = group
                    }
                },
                navigationRequest = pendingNavigationRequest,
                onNavigationRequestHandled = { pendingNavigationRequest = null },
                onNavigationTargetMissing = { message ->
                    snackbarModel = GeoVaultSnackbarModel(
                        id = "shared-nav-missing-${message.hashCode()}-${System.currentTimeMillis()}",
                        message = message,
                    )
                },
                onOpenTrackerOnMap = onOpenTrackerOnMap,
                onOpenGroupOnMap = onOpenGroupOnMap,
                onViewTrackerParams = { tracker ->
                    onRequestTrackerParams(tracker.toTrackerParamsRouteArgs())
                },
                onOpenGroupActions = { group, highlightedTrackerId ->
                    groupActionsDialog = GroupMembersOverlayState(
                        groupId = group.id,
                        highlightedTrackerId = highlightedTrackerId,
                    )
                },
            )
        },
        tabOverlay = {
            if (groupActionsDialog != null) {
                val dialog = groupActionsDialog!!
                val group = state.groups.find { it.id == dialog.groupId }
                if (group == null) {
                    LaunchedEffect(dialog.groupId) {
                        groupActionsDialog = null
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GroupActionsScreen(
                            group = group,
                            allTrackers = state.trackers,
                            highlightedTrackerId = dialog.highlightedTrackerId,
                            onDismiss = { groupActionsDialog = null },
                            onViewTrackerOnMap = { trackerId ->
                                onOpenTrackerOnMap(trackerId, null)
                            },
                            onViewTrackerParams = { tracker ->
                                onRequestTrackerParams(tracker.toTrackerParamsRouteArgs())
                            },
                            onViewTrackerInList = { trackerId ->
                                groupActionsDialog = null
                                vm.clearSharedListQuery()
                                vm.openFromNavigationSubTab(SharedSubTab.SHARED)
                                pendingNavigationRequest = SharedHostNavigationRequest(
                                    subTab = SharedSubTab.SHARED,
                                    trackerId = trackerId,
                                    focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
                                )
                            },
                            onEditGroup = { _ -> },
                            onViewGroupOnMap = { groupId ->
                                onOpenGroupOnMap(groupId)
                            },
                        )
                    }
                }
            } else if (editSharedGroup != null) {
                val group = editSharedGroup!!
                val leavePending = state.pendingRemoveActionKeys.contains(
                    vm.editGroupLeavePendingKey(group.id)
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    SharedGroupEditScreen(
                        group = group,
                        isLeavePending = leavePending,
                        onDismiss = { editSharedGroup = null },
                        onLeaveGroup = {
                            pendingConfirmAction = SharedConfirmAction.LeaveGroup(
                                groupId = group.id,
                                groupName = group.name,
                            )
                        },
                    )
                }
            } else if (editSharedTracker != null) {
                val tracker = editSharedTracker!!
                val unsubscribePending = state.pendingRemoveActionKeys.contains(
                    vm.editTrackerUnsubscribePendingKey(tracker.id)
                )
                val leaveSharePending = state.pendingRemoveActionKeys.contains(
                    vm.editTrackerLeaveSharePendingKey(tracker.id)
                )
                val actions = SharedEditActionPolicy.trackerActions(tracker)
                Box(modifier = Modifier.fillMaxSize()) {
                    SharedTrackerEditScreen(
                        tracker = tracker,
                        canUnsubscribe = actions.canUnsubscribe,
                        canLeaveShare = actions.canLeaveShare,
                        isUnsubscribePending = unsubscribePending,
                        isLeaveSharePending = leaveSharePending,
                        onDismiss = { editSharedTracker = null },
                        onUnsubscribe = { pendingConfirmAction = SharedConfirmAction.UnsubscribeTracker(tracker) },
                        onLeaveShare = { pendingConfirmAction = SharedConfirmAction.LeaveShareTracker(tracker) },
                    )
                }
            }
            TrackerParamsOverlayLayer()
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
                is SharedConfirmAction.UnsubscribeTracker -> vm.requestEditSharedTrackerUnsubscribe(action.tracker.id)
                is SharedConfirmAction.LeaveShareTracker -> vm.requestEditSharedTrackerLeaveShare(action.tracker.id)
                is SharedConfirmAction.LeaveTracker -> vm.leaveTracker(action.tracker)
                is SharedConfirmAction.LeaveGroup -> vm.requestEditSharedGroupLeave(action.groupId)
                is SharedConfirmAction.RejectIncomingShare -> vm.leaveIncomingShare(action.trackerId)
                is SharedConfirmAction.RemoveDiscoverOnMapTracker -> {
                    vm.requestDiscoverOnMapTrackerRemove(action.trackerId)
                }
                is SharedConfirmAction.UnsubscribeAllInGroup -> vm.unsubscribeAllTracksInGroup(action.trackIds)
            }
            pendingConfirmAction = null
        }
    )
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
    onSharedListQueryChanged: (String) -> Unit,
    onPublicQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onLeaveTracker: (Tracker) -> Unit,
    onLeaveGroup: (String, String) -> Unit,
    onAcceptGroup: (String) -> Unit,
    onSubscribeIncomingTracker: (String) -> Unit,
    onLeaveIncomingShare: (String, String) -> Unit,
    onRemoveOnMapTracker: (String, String) -> Unit,
    onRemoveOnMapGroup: (String) -> Unit,
    onSubscribePublicTracker: (String) -> Unit,
    onSubscribePublicGroup: (String) -> Unit,
    onUnsubscribePublicTracker: (String) -> Unit,
    onUnsubscribePublicGroup: (String) -> Unit,
    onUnsubscribeAllInGroup: (String, List<String>) -> Unit,
    onEditSharedTracker: (Tracker) -> Unit,
    onEditSharedGroup: (Group) -> Unit,
    navigationRequest: SharedHostNavigationRequest?,
    onNavigationRequestHandled: () -> Unit,
    onNavigationTargetMissing: (String) -> Unit,
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit,
    onOpenGroupOnMap: (groupId: String) -> Unit,
    onViewTrackerParams: (Tracker) -> Unit,
    onOpenGroupActions: (group: Group, highlightedTrackerId: String?) -> Unit,
) {
    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.SHARED_OVERLAY,
        canGoBack = { state.viewMode != SharedViewMode.SHARED_LIST },
        onBack = {
            onShowSharedList()
            true
        },
    )
    var highlightedItemKey by remember { mutableStateOf<String?>(null) }
    var navigationRefreshAttempts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val pendingAddActionKeys = state.pendingAddActionKeys
    val pendingRemoveActionKeys = state.pendingRemoveActionKeys
    val sharedListState = rememberLazyListState()
    val sharedListRows = state.sharedListRows
    val filteredDiscoverOnMapTrackers = state.filteredSections.discoverOnMyMapTrackers
    val filteredDiscoverOnMapGroups = state.filteredSections.discoverOnMyMapGroups
    val filteredIncomingTrackers = state.filteredSections.incomingTrackers
    val filteredIncomingGroups = state.filteredSections.incomingGroups
    val filteredPublicTrackers = state.filteredSections.publicTrackers
    val filteredPublicGroups = state.filteredSections.publicGroups
    val navigationTargetMissingMessage = stringResource(R.string.shared_navigation_target_not_found)

    LaunchedEffect(navigationRequest, sharedListRows, state.isLoading) {
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
                val trackerOffset = sharedListRows.indexOfFirst { row ->
                    row is SharedListRowModel.TrackerRow && row.tracker.id == request.trackerId
                }
                if (trackerOffset >= 0) {
                    Pair(trackerOffset, "s-t-${request.trackerId}")
                } else {
                    val groupOffset = sharedListRows.indexOfFirst { row ->
                        row is SharedListRowModel.GroupRow &&
                            row.group.track_ids.orEmpty().any { it.trim() == request.trackerId }
                    }
                    if (groupOffset >= 0) {
                        val groupId = (sharedListRows[groupOffset] as SharedListRowModel.GroupRow).group.id
                        Pair(groupOffset, "s-g-$groupId")
                    } else {
                        Pair(-1, null)
                    }
                }
            }
            !request.groupId.isNullOrBlank() -> {
                val groupOffset = sharedListRows.indexOfFirst { row ->
                    row is SharedListRowModel.GroupRow && row.group.id == request.groupId
                }
                if (groupOffset >= 0) Pair(groupOffset, "s-g-${request.groupId}") else Pair(-1, null)
            }
            else -> Pair(-1, null)
        }
        val requestKey = request.toNavigationKey()
        if (targetIndex < 0 && (request.trackerId != null || request.groupId != null)) {
            if (state.isLoading) {
                return@LaunchedEffect
            }
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
        } else if (request.trackerId != null || request.groupId != null) {
            onNavigationTargetMissing(navigationTargetMissingMessage)
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
                    sharedListRows = sharedListRows,
                    highlightedItemKey = highlightedItemKey,
                    listState = sharedListState,
                    onRefresh = onRefresh,
                    onSharedListQueryChanged = onSharedListQueryChanged,
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
                    pendingAddActionKeys = pendingAddActionKeys,
                    pendingRemoveActionKeys = pendingRemoveActionKeys,
                    onModeChanged = onDiscoverModeChanged,
                    onOnMyMapQueryChanged = onDiscoverOnMapQueryChanged,
                    onIncomingQueryChanged = onDiscoverIncomingQueryChanged,
                    onRefresh = onRefresh,
                    onClose = onShowSharedList,
                    onAddIncomingTracker = onSubscribeIncomingTracker,
                    onLeaveIncomingGroup = { groupId, groupName -> onLeaveGroup(groupId, groupName) },
                    onAcceptIncomingGroup = onAcceptGroup,
                    onRemoveOnMapTracker = onRemoveOnMapTracker,
                    onRemoveOnMapGroup = onRemoveOnMapGroup,
                )
            }
            SharedViewMode.PUBLIC_OVERLAY -> {
                PublicOverlaySurface(
                    state = state,
                    filteredPublicTrackers = filteredPublicTrackers,
                    filteredPublicGroups = filteredPublicGroups,
                    pendingAddActionKeys = pendingAddActionKeys,
                    pendingRemoveActionKeys = pendingRemoveActionKeys,
                    onQueryChanged = onPublicQueryChanged,
                    onRefresh = onRefresh,
                    onClose = onShowSharedList,
                    onAddTracker = onSubscribePublicTracker,
                    onRemoveTracker = onUnsubscribePublicTracker,
                    onAddGroup = onSubscribePublicGroup,
                    onRemoveGroup = onUnsubscribePublicGroup,
                )
            }
        }
        if (state.viewMode == SharedViewMode.SHARED_LIST) {
            val incomingTrackerCount = state.incomingTrackers.size
            GeoVaultFloatingActionButtonWithTooltip(
                onClick = onShowPublicOverlay,
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary,
                tooltip = stringResource(R.string.tooltip_shared_fab_public),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 88.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = stringResource(R.string.shared_action_open_public),
                )
            }
            GeoVaultFloatingActionButtonWithTooltip(
                onClick = onShowDiscoverOverlay,
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary,
                tooltip = stringResource(R.string.tooltip_shared_fab_add),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                DiscoverFabIcon(
                    incomingTrackerCount = incomingTrackerCount,
                    contentDescription = stringResource(R.string.shared_action_open_discover),
                )
            }
        }
    }
}

@Composable
private fun DiscoverFabIcon(
    incomingTrackerCount: Int,
    contentDescription: String,
) {
    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = contentDescription,
        )
        if (incomingTrackerCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .size(10.dp)
                    .background(GeoVaultColorTokens.MainYellow, CircleShape),
            )
        }
    }
}

@Composable
private fun SharedMainListSurface(
    state: SharedUiState,
    sharedListRows: List<SharedListRowModel>,
    highlightedItemKey: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRefresh: () -> Unit,
    onSharedListQueryChanged: (String) -> Unit,
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
        Column(modifier = Modifier.fillMaxSize()) {
            GeoVaultSearchField(
                value = state.sharedListQuery,
                onValueChange = onSharedListQueryChanged,
                placeholder = stringResource(R.string.shared_search_hint),
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sharedListRows.isEmpty()) {
                    item {
                        SharedEmptyState()
                    }
                } else {
                    items(
                        items = sharedListRows,
                        key = { it.key },
                    ) { row ->
                        when (row) {
                            is SharedListRowModel.TrackerRow -> {
                                val tracker = row.tracker
                                TrackerItemCard(
                                    model = TrackerItemCardModel(
                                        title = tracker.name,
                                        chevronColorHex = tracker.color,
                                        lastUpdateText = row.lastUpdateMs?.let(::formatSharedListDate)
                                            ?: stringResource(R.string.waiting_for_data),
                                        coordinatesText = if (row.latitude != null && row.longitude != null) {
                                            CoordinateFormat.DECIMAL_4.formatLatLon(row.latitude, row.longitude)
                                        } else {
                                            null
                                        },
                                        ownerEmail = row.ownerEmail,
                                        isHighlighted = highlightedItemKey == row.key,
                                        isSelected = row.isSelected,
                                        canOpenMap = row.canOpenMap,
                                        canEdit = row.canEdit,
                                    ),
                                    onOpenMap = { onOpenTrackerOnMap(tracker.id, tracker.name) },
                                    onViewParams = { onViewTrackerParams(tracker) },
                                    onEdit = { onEditSharedTracker(tracker) },
                                    enabled = enabled,
                                )
                            }
                            is SharedListRowModel.GroupRow -> {
                                val group = row.group
                                GroupItemCard(
                                    model = GroupItemCardModel(
                                        title = group.name,
                                        ownerEmail = row.ownerEmail,
                                        trackerCount = row.trackerCount,
                                        isPending = false,
                                        isHighlighted = highlightedItemKey == row.key,
                                        canOpenMap = true,
                                        canEdit = row.canEdit,
                                        canOpenActions = true,
                                    ),
                                    onOpenActions = { onOpenGroupActions(group, null) },
                                    onOpenMap = { onOpenGroupOnMap(group.id) },
                                    onEdit = { onEditSharedGroup(group) },
                                    enabled = enabled,
                                )
                            }
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
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
    pendingAddActionKeys: Set<String>,
    pendingRemoveActionKeys: Set<String>,
    onModeChanged: (DiscoverOverlayMode) -> Unit,
    onOnMyMapQueryChanged: (String) -> Unit,
    onIncomingQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onAddIncomingTracker: (String) -> Unit,
    onLeaveIncomingGroup: (String, String) -> Unit,
    onAcceptIncomingGroup: (String) -> Unit,
    onRemoveOnMapTracker: (String, String) -> Unit,
    onRemoveOnMapGroup: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val isRequiredDataMissing = state.availableToAdd == null || !state.hasCompletedInitialLoad
    val hasInlineMutation = pendingAddActionKeys.isNotEmpty() || pendingRemoveActionKeys.isNotEmpty()
    val overlayLoading = (state.isLoading || isRequiredDataMissing) && !hasInlineMutation
    val tabs = listOf(
        GeoVaultTab(
            value = DiscoverOverlayMode.ON_MY_MAP,
            label = stringResource(R.string.shared_discover_tab_on_my_map),
        ),
        GeoVaultTab(
            value = DiscoverOverlayMode.INCOMING,
            label = stringResource(R.string.shared_discover_tab_incoming),
            countBadge = state.incomingTrackers.size,
        ),
    )
    val loadingTrackersText = stringResource(R.string.loading_trackers)
    val selectedIndex = tabs.indexOfFirst { it.value == state.discoverMode }.let { if (it >= 0) it else 0 }
    val pagerState = rememberPagerState(
        initialPage = selectedIndex,
        pageCount = { tabs.size },
    )
    LaunchedEffect(selectedIndex, tabs) {
        if (pagerState.currentPage != selectedIndex && selectedIndex in tabs.indices) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    LaunchedEffect(pagerState.settledPage, tabs) {
        val nextTab = tabs.getOrNull(pagerState.settledPage)?.value ?: return@LaunchedEffect
        if (nextTab != state.discoverMode) {
            onModeChanged(nextTab)
        }
    }
    val activeTab = tabs.getOrNull(pagerState.settledPage)?.value ?: state.discoverMode

    GeoVaultSubViewScaffold(
        title = stringResource(R.string.shared_discover_overlay_title),
        onClose = onClose,
        onLeaveComposition = onClose,
        closeContentDescription = stringResource(R.string.close),
        headerExtras = {
            GeoVaultTabBar(
                tabs = tabs,
                selectedTab = state.discoverMode,
                onTabSelected = onModeChanged,
                indicatorPage = pagerState.currentPage,
                indicatorOffsetFraction = pagerState.currentPageOffsetFraction,
            )
            val activeQuery = if (activeTab == DiscoverOverlayMode.ON_MY_MAP) {
                state.discoverOnMapQuery
            } else {
                state.discoverIncomingQuery
            }
            GeoVaultSearchField(
                value = activeQuery,
                onValueChange = { next ->
                    if (activeTab == DiscoverOverlayMode.ON_MY_MAP) onOnMyMapQueryChanged(next)
                    else onIncomingQueryChanged(next)
                },
                placeholder = stringResource(R.string.shared_search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                enabled = !overlayLoading,
            )
            Divider()
        },
    ) { innerPadding ->
        GeoVaultPullRefreshLoadingContainer(
            refreshing = overlayLoading,
            showBlockingLoader = false,
            onRefresh = onRefresh,
            canRefresh = !overlayLoading,
            loadingText = loadingTrackersText,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val tab = tabs.getOrNull(page)?.value ?: return@HorizontalPager
                Box(modifier = Modifier.fillMaxSize()) {
                    DiscoverOverlayTabContent(
                        tab = tab,
                        state = state,
                        listState = listState,
                        filteredOnMyMapTrackers = filteredOnMyMapTrackers,
                        filteredOnMyMapGroups = filteredOnMyMapGroups,
                        filteredIncomingTrackers = filteredIncomingTrackers,
                        filteredIncomingGroups = filteredIncomingGroups,
                        pendingAddActionKeys = pendingAddActionKeys,
                        pendingRemoveActionKeys = pendingRemoveActionKeys,
                        overlayLoading = overlayLoading,
                        onAddIncomingTracker = onAddIncomingTracker,
                        onLeaveIncomingGroup = onLeaveIncomingGroup,
                        onAcceptIncomingGroup = onAcceptIncomingGroup,
                        onRemoveOnMapTracker = onRemoveOnMapTracker,
                        onRemoveOnMapGroup = onRemoveOnMapGroup,
                    )
                    if (overlayLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colors.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            GeoVaultLoadingSpinner(bottomText = loadingTrackersText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverOverlayTabContent(
    tab: DiscoverOverlayMode,
    state: SharedUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    filteredOnMyMapTrackers: List<AvailableToAddItem>,
    filteredOnMyMapGroups: List<AvailableToAddGroup>,
    filteredIncomingTrackers: List<AvailableToAddItem>,
    filteredIncomingGroups: List<AvailableToAddGroup>,
    pendingAddActionKeys: Set<String>,
    pendingRemoveActionKeys: Set<String>,
    overlayLoading: Boolean,
    onAddIncomingTracker: (String) -> Unit,
    onLeaveIncomingGroup: (String, String) -> Unit,
    onAcceptIncomingGroup: (String) -> Unit,
    onRemoveOnMapTracker: (String, String) -> Unit,
    onRemoveOnMapGroup: (String) -> Unit,
) {
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
                val isPendingRemove = pendingRemoveActionKeys.contains(
                    TrackerAddRemoveKeyPolicy.sharedMutationKey(
                        SharedAddRemoveOperation.DiscoverOnMapTrackerRemove(item.id)
                    )
                )
                DiscoverOnMapTrackerCard(
                    item = item,
                    onRemove = { onRemoveOnMapTracker(item.id, item.name) },
                    isPendingRemove = isPendingRemove,
                    enabled = !overlayLoading && !isPendingRemove,
                )
            }
            items(filteredOnMyMapGroups, key = { "m-g-${it.id}" }) { group ->
                val isPendingRemove = pendingRemoveActionKeys.contains(
                    TrackerAddRemoveKeyPolicy.sharedMutationKey(
                        SharedAddRemoveOperation.DiscoverOnMapGroupRemove(group.id)
                    )
                )
                DiscoverOnMapGroupCard(
                    group = group,
                    onRemove = { onRemoveOnMapGroup(group.id) },
                    isPendingRemove = isPendingRemove,
                    enabled = !overlayLoading && !isPendingRemove,
                )
            }
        } else {
            if (filteredIncomingTrackers.isEmpty() && filteredIncomingGroups.isEmpty()) {
                item { EmptyLine(stringResource(R.string.shared_empty_discover_incoming)) }
            }
            items(filteredIncomingTrackers, key = { "d-t-${it.id}" }) { item ->
                val isAdded = state.isIncomingTrackerAdded(item.id)
                val isPending = pendingAddActionKeys.contains(
                    TrackerAddRemoveKeyPolicy.sharedMutationKey(
                        SharedAddRemoveOperation.IncomingTrackerAdd(item.id)
                    )
                )
                val isPendingRemove = pendingRemoveActionKeys.contains(
                    TrackerAddRemoveKeyPolicy.sharedMutationKey(
                        SharedAddRemoveOperation.DiscoverOnMapTrackerRemove(item.id)
                    )
                )
                DiscoverIncomingTrackerCard(
                    item = item,
                    onAdd = { onAddIncomingTracker(item.id) },
                    onRemove = { onRemoveOnMapTracker(item.id, item.name) },
                    isAdded = isAdded,
                    isPendingAdd = isPending,
                    isPendingRemove = isPendingRemove,
                    isHighlighted = false,
                    enabled = !overlayLoading && !isPending && !isPendingRemove,
                )
            }
            items(filteredIncomingGroups, key = { "d-g-${it.id}" }) { group ->
                val isAdded = state.isIncomingGroupAdded(group.id)
                val isPending = pendingAddActionKeys.contains(
                    TrackerAddRemoveKeyPolicy.sharedMutationKey(
                        SharedAddRemoveOperation.IncomingGroupAccept(group.id)
                    )
                )
                val isPendingRemove = pendingRemoveActionKeys.contains(
                    TrackerAddRemoveKeyPolicy.sharedMutationKey(
                        SharedAddRemoveOperation.DiscoverOnMapGroupRemove(group.id)
                    )
                )
                DiscoverIncomingGroupCard(
                    group = group,
                    onAccept = { onAcceptIncomingGroup(group.id) },
                    onLeave = { onLeaveIncomingGroup(group.id, group.name) },
                    onRemove = { onRemoveOnMapGroup(group.id) },
                    isAdded = isAdded,
                    isPendingAccept = isPending,
                    isPendingRemove = isPendingRemove,
                    isHighlighted = false,
                    enabled = !overlayLoading && !isPending && !isPendingRemove,
                )
            }
        }
    }
}

@Composable
private fun PublicOverlaySurface(
    state: SharedUiState,
    filteredPublicTrackers: List<AvailableToAddItem>,
    filteredPublicGroups: List<AvailableToAddGroup>,
    pendingAddActionKeys: Set<String>,
    pendingRemoveActionKeys: Set<String>,
    onQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onAddTracker: (String) -> Unit,
    onRemoveTracker: (String) -> Unit,
    onAddGroup: (String) -> Unit,
    onRemoveGroup: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val isRequiredDataMissing = state.availableToAdd == null || !state.hasCompletedInitialLoad
    val hasInlineMutation = pendingAddActionKeys.isNotEmpty() || pendingRemoveActionKeys.isNotEmpty()
    val overlayLoading = (state.isLoading || isRequiredDataMissing) && !hasInlineMutation
    GeoVaultSubViewScaffold(
        title = stringResource(R.string.shared_public_overlay_title),
        onClose = onClose,
        onLeaveComposition = onClose,
        closeContentDescription = stringResource(R.string.close),
        headerExtras = {
            Divider()
            GeoVaultSearchField(
                value = state.publicQuery,
                onValueChange = onQueryChanged,
                placeholder = stringResource(R.string.shared_search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                enabled = !overlayLoading,
            )
        },
    ) { innerPadding ->
        GeoVaultPullRefreshLoadingContainer(
            refreshing = overlayLoading,
            showBlockingLoader = overlayLoading,
            onRefresh = onRefresh,
            canRefresh = !overlayLoading,
            loadingText = stringResource(R.string.loading_trackers),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
                    val isPendingAdd = pendingAddActionKeys.contains(
                        TrackerAddRemoveKeyPolicy.sharedMutationKey(
                            SharedAddRemoveOperation.PublicTrackerAdd(item.id)
                        )
                    )
                    val isPendingRemove = pendingRemoveActionKeys.contains(
                        TrackerAddRemoveKeyPolicy.sharedMutationKey(
                            SharedAddRemoveOperation.PublicTrackerRemove(item.id)
                        )
                    )
                    PublicAddTrackerCard(
                        item = item,
                        isAdded = state.isPublicTrackerAdded(item.id),
                        onAdd = { onAddTracker(item.id) },
                        onRemove = { onRemoveTracker(item.id) },
                        isPendingAdd = isPendingAdd,
                        isPendingRemove = isPendingRemove,
                        isHighlighted = false,
                        enabled = !overlayLoading && !isPendingAdd && !isPendingRemove,
                    )
                }
                items(filteredPublicGroups, key = { "p-g-${it.id}" }) { group ->
                    val isPendingAdd = pendingAddActionKeys.contains(
                        TrackerAddRemoveKeyPolicy.sharedMutationKey(
                            SharedAddRemoveOperation.PublicGroupAdd(group.id)
                        )
                    )
                    val isPendingRemove = pendingRemoveActionKeys.contains(
                        TrackerAddRemoveKeyPolicy.sharedMutationKey(
                            SharedAddRemoveOperation.PublicGroupRemove(group.id)
                        )
                    )
                    PublicAddGroupCard(
                        group = group,
                        isAdded = state.isPublicGroupAdded(group.id),
                        onAddGroup = { onAddGroup(group.id) },
                        onRemoveGroup = { onRemoveGroup(group.id) },
                        isPendingAdd = isPendingAdd,
                        isPendingRemove = isPendingRemove,
                        isHighlighted = false,
                        enabled = !overlayLoading && !isPendingAdd && !isPendingRemove,
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
    isPendingRemove: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = item.name,
        ownerEmail = item.owner_email,
        iconRes = R.drawable.ic_chevron_track,
        iconTint = TrackerChevronStylePolicy.DefaultAddRowTint,
        flags = GeoVaultAddRemoveRowFlags(
            isPendingRemove = isPendingRemove,
            isAdded = !isPendingRemove,
        ),
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = {},
        onRemove = onRemove,
        removeIconTooltip = stringResource(R.string.tooltip_discover_row_remove),
    )
}

@Composable
private fun DiscoverOnMapGroupCard(
    group: AvailableToAddGroup,
    onRemove: () -> Unit,
    isPendingRemove: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = group.name,
        ownerEmail = group.owner_email,
        iconRes = R.drawable.ic_groups,
        iconTint = TrackerChevronStylePolicy.DefaultAddRowTint,
        flags = GeoVaultAddRemoveRowFlags(
            isPendingRemove = isPendingRemove,
            isAdded = !isPendingRemove,
        ),
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = {},
        onRemove = onRemove,
        removeIconTooltip = stringResource(R.string.tooltip_discover_row_remove),
    )
}

private sealed interface SharedConfirmAction {
    data class UnsubscribeTracker(val tracker: Tracker) : SharedConfirmAction
    data class LeaveShareTracker(val tracker: Tracker) : SharedConfirmAction
    data class LeaveTracker(val tracker: Tracker) : SharedConfirmAction
    data class LeaveGroup(val groupId: String, val groupName: String) : SharedConfirmAction
    data class RejectIncomingShare(val trackerId: String, val trackerName: String) : SharedConfirmAction
    data class RemoveDiscoverOnMapTracker(val trackerId: String, val trackerName: String) : SharedConfirmAction
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
        is SharedConfirmAction.UnsubscribeTracker -> {
            title = stringResource(R.string.confirm_unsubscribe_title)
            message = stringResource(R.string.confirm_unsubscribe_message, action.tracker.name) +
                "\n\n" +
                stringResource(R.string.confirm_unsubscribe_help)
            confirmLabel = stringResource(R.string.trackers_action_unsubscribe)
        }
        is SharedConfirmAction.LeaveShareTracker -> {
            title = stringResource(R.string.confirm_leave_share_title)
            message = stringResource(R.string.confirm_leave_share_message, action.tracker.name) +
                "\n\n" +
                stringResource(R.string.confirm_leave_share_help)
            confirmLabel = stringResource(R.string.trackers_action_leave_share)
        }
        is SharedConfirmAction.LeaveTracker -> {
            val leaveKind = OwnershipActionPolicy.trackerLeaveKind(action.tracker)
            if (leaveKind == TrackerLeaveKind.LeaveShare) {
                title = stringResource(R.string.confirm_leave_share_title)
                message = stringResource(R.string.confirm_leave_share_message, action.tracker.name) +
                    "\n\n" +
                    stringResource(R.string.confirm_leave_share_help)
                confirmLabel = stringResource(R.string.trackers_action_leave_share)
            } else {
                title = stringResource(R.string.confirm_unsubscribe_title)
                message = stringResource(R.string.confirm_unsubscribe_message, action.tracker.name) +
                    "\n\n" +
                    stringResource(R.string.confirm_unsubscribe_help)
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
        is SharedConfirmAction.RemoveDiscoverOnMapTracker -> {
            title = stringResource(R.string.confirm_remove_from_map_title)
            message = stringResource(R.string.confirm_remove_from_map_message, action.trackerName)
            confirmLabel = stringResource(R.string.shared_action_remove_from_map)
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
    GeoVaultEmptyState(message = text, fillMaxSize = false)
}

private fun SharedHostNavigationRequest.toNavigationKey(): String {
    return "${subTab.name}|${focus.name}|${trackerId.orEmpty()}|${groupId.orEmpty()}"
}

private fun formatSharedListDate(timestampMs: Long): String =
    GeoVaultDateTimeFormat.formatLocalDateTime(timestampMs)

@Composable
private fun DiscoverIncomingTrackerCard(
    item: AvailableToAddItem,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    isAdded: Boolean,
    isPendingAdd: Boolean,
    isPendingRemove: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = item.name,
        ownerEmail = item.owner_email,
        iconRes = R.drawable.ic_chevron_track,
        iconTint = TrackerChevronStylePolicy.DefaultAddRowTint,
        flags = GeoVaultAddRemoveRowFlags(
            isPendingAdd = isPendingAdd,
            isPendingRemove = isPendingRemove,
            isAdded = isAdded,
        ),
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = if (isAdded) { { } } else onAdd,
        onRemove = onRemove,
        addIconTooltip = stringResource(R.string.tooltip_discover_row_add),
        removeIconTooltip = stringResource(R.string.tooltip_discover_row_remove),
    )
}

@Composable
private fun DiscoverIncomingGroupCard(
    group: AvailableToAddGroup,
    onAccept: () -> Unit,
    onLeave: () -> Unit,
    onRemove: () -> Unit,
    isAdded: Boolean,
    isPendingAccept: Boolean,
    isPendingRemove: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = group.name,
        ownerEmail = group.owner_email,
        iconRes = R.drawable.ic_groups,
        iconTint = TrackerChevronStylePolicy.DefaultAddRowTint,
        flags = GeoVaultAddRemoveRowFlags(
            isPendingAdd = isPendingAccept,
            isPendingRemove = isPendingRemove,
            isAdded = isAdded,
        ),
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = if (isAdded) { { } } else onAccept,
        onRemove = if (isAdded) onRemove else onLeave,
        addIconTooltip = stringResource(R.string.tooltip_discover_row_add),
        removeIconTooltip = if (isAdded) {
            stringResource(R.string.tooltip_discover_row_remove)
        } else {
            null
        },
    )
}

@Composable
private fun PublicAddTrackerCard(
    item: AvailableToAddItem,
    isAdded: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    isPendingAdd: Boolean,
    isPendingRemove: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = item.name,
        ownerEmail = item.owner_email,
        iconRes = R.drawable.ic_chevron_track,
        iconTint = TrackerChevronStylePolicy.DefaultAddRowTint,
        flags = GeoVaultAddRemoveRowFlags(
            isPendingAdd = isPendingAdd,
            isPendingRemove = isPendingRemove,
            isAdded = isAdded,
        ),
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = onAdd,
        onRemove = onRemove,
        addIconTooltip = stringResource(R.string.tooltip_public_row_add),
        removeIconTooltip = stringResource(R.string.tooltip_public_row_remove),
    )
}

@Composable
private fun PublicAddGroupCard(
    group: AvailableToAddGroup,
    isAdded: Boolean,
    onAddGroup: () -> Unit,
    onRemoveGroup: () -> Unit,
    isPendingAdd: Boolean,
    isPendingRemove: Boolean,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    TrackerAddRowCard(
        name = group.name,
        ownerEmail = group.owner_email,
        iconRes = R.drawable.ic_groups,
        iconTint = TrackerChevronStylePolicy.DefaultAddRowTint,
        flags = GeoVaultAddRemoveRowFlags(
            isPendingAdd = isPendingAdd,
            isPendingRemove = isPendingRemove,
            isAdded = isAdded,
        ),
        borderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.16f),
        enabled = enabled,
        onAdd = onAddGroup,
        onRemove = onRemoveGroup,
        addIconTooltip = stringResource(R.string.tooltip_public_row_add),
        removeIconTooltip = stringResource(R.string.tooltip_public_row_remove),
    )
}

