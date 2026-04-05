package com.geovault.tracker.ui

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.AlertDialog
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.ui.components.GeoVaultCheckmark
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultToggle
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.OwnershipActionPolicy
import com.geovault.tracker.presentation.GroupShareVisibility
import com.geovault.tracker.presentation.TrackerLeaveKind
import com.geovault.tracker.presentation.TrackerShareVisibility
import com.geovault.tracker.presentation.TrackerSharingSettingsPolicy
import com.geovault.tracker.presentation.TrackerKmlExportEvent
import com.geovault.tracker.presentation.TrackersGroupsDialog
import com.geovault.tracker.presentation.TrackersGroupsSubTab
import com.geovault.tracker.presentation.TrackersGroupsUiState
import com.geovault.tracker.presentation.TrackersGroupsViewModel
import kotlinx.coroutines.delay

private val LIST_DATE_FORMAT = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun TrackersScreen(
    isAuthenticated: Boolean,
    serverUrl: String,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    isConnecting: Boolean,
    isServerAccessible: Boolean,
    onOpenSettings: () -> Unit,
    navigationRequest: TrackersHostNavigationRequest? = null,
    onNavigationTargetConsumed: () -> Unit = {},
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit = { _, _ -> },
    onOpenGroupOnMap: (groupId: String) -> Unit = {},
    onRequestTrackerParams: (TrackerParamsUiModel) -> Unit = {},
) {
    val vm: TrackersGroupsViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    var pendingKmlBytes by remember { mutableStateOf<ByteArray?>(null) }
    val createKmlDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri: Uri? ->
        val bytes = pendingKmlBytes
        pendingKmlBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        val stream = context.contentResolver.openOutputStream(uri)
            ?: error("Unable to open output stream for KML export URI: $uri")
        stream.use { it.write(bytes) }
        vm.postUserMessage(context.getString(R.string.trackers_kml_exported))
    }
    LaunchedEffect(vm) {
        vm.kmlExportEvents.collect { event: TrackerKmlExportEvent ->
            pendingKmlBytes = event.bytes
            createKmlDocumentLauncher.launch("${event.fileBaseName}.kml")
        }
    }
    LaunchedEffect(vm) {
        vm.toastEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    var pendingConfirmAction by remember { mutableStateOf<TrackersConfirmAction?>(null) }
    var groupMembershipDialog by remember { mutableStateOf<GroupMembershipDialogState?>(null) }
    var pendingNavigationRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var localNavigationRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var groupActionsDialog by remember { mutableStateOf<GroupMembersOverlayState?>(null) }
    val activeTrackerEditLoadingDialog = state.dialog as? TrackersGroupsDialog.EditTrackerLoading
    val activeTrackerEditDialog = state.dialog as? TrackersGroupsDialog.EditTracker

    LaunchedEffect(navigationRequest) {
        val request = navigationRequest ?: return@LaunchedEffect
        vm.setSubTab(request.subTab)
        pendingNavigationRequest = request
        onNavigationTargetConsumed()
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
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        authenticatedFloatingAction = {
            if (activeTrackerEditDialog == null && activeTrackerEditLoadingDialog == null) {
                FloatingActionButton(
                    onClick = {
                        if (state.subTab == TrackersGroupsSubTab.TRACKERS) {
                            vm.openCreateTrackerDialog()
                        } else {
                            vm.openCreateGroupDialog()
                        }
                    },
                    backgroundColor = GeoVaultColorTokens.PrimaryBlue,
                    contentColor = MaterialTheme.colors.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (state.subTab == TrackersGroupsSubTab.TRACKERS) {
                            stringResource(R.string.trackers_action_create_tracker)
                        } else {
                            stringResource(R.string.trackers_action_create_group)
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        authenticatedMainContent = {
            if (activeTrackerEditDialog != null) {
                TrackerEditScreen(
                    dialog = activeTrackerEditDialog,
                    shareRecipientUsers = state.shareRecipientUsers,
                    isShareRecipientSuggestionsLoading = state.isShareRecipientSuggestionsLoading,
                    isKmlExportLoading = state.isKmlExportLoading,
                    isSaving = state.isLoading,
                    onDismiss = vm::dismissDialog,
                    onReloadShareRecipients = vm::refreshShareRecipientSuggestions,
                    onNameDraftChanged = vm::updateEditTrackerDraft,
                    onColorDraftChanged = vm::updateEditTrackerColorDraft,
                    onSetAsSelectedChanged = vm::updateEditTrackerSetAsSelected,
                    onHiddenChanged = vm::updateEditTrackerHidden,
                    onRecentDataWindowChanged = vm::updateEditTrackerRecentDataWindow,
                    onVisibilityChanged = vm::updateEditTrackerVisibility,
                    onShareParamsWithRecipientsChanged = vm::updateEditTrackerShareParamsWithRecipients,
                    onAllowGroupReshareChanged = vm::updateEditTrackerAllowGroupReshare,
                    onToggleSharedEmail = vm::toggleEditTrackerSharedEmailSelection,
                    onWorldShareEnabledChanged = vm::updateEditTrackerWorldShareEnabled,
                    onShareParamsWithWorldChanged = vm::updateEditTrackerShareParamsWithWorld,
                    onClearHistory = {
                        pendingConfirmAction = TrackersConfirmAction.ClearTrackerHistory(
                            trackerId = activeTrackerEditDialog.tracker.id,
                            trackerName = activeTrackerEditDialog.tracker.name,
                        )
                    },
                    onDeleteTracker = {
                        pendingConfirmAction = TrackersConfirmAction.DeleteTracker(
                            trackerId = activeTrackerEditDialog.tracker.id,
                            trackerName = activeTrackerEditDialog.tracker.name,
                        )
                    },
                    onExportKml = {
                        vm.exportTrackerKml(
                            trackerId = activeTrackerEditDialog.tracker.id,
                            trackerDisplayName = activeTrackerEditDialog.nameDraft.ifBlank {
                                activeTrackerEditDialog.tracker.name
                            },
                        )
                    },
                    onSave = vm::submitEditTracker,
                )
            } else if (activeTrackerEditLoadingDialog != null) {
                TrackerEditLoadingSurface(
                    trackerName = activeTrackerEditLoadingDialog.trackerName,
                )
            } else {
                TrackersGroupsAuthenticatedBody(
                    state = state,
                    isServerAccessible = isServerAccessible,
                    isConnecting = isConnecting,
                    onSubTabSelected = vm::setSubTab,
                    onPullRefresh = { vm.refreshAll(asPullRefresh = true) },
                    onToggleTrackerMapHidden = vm::toggleTrackerHiddenOnMap,
                    onToggleGroupMapHidden = vm::toggleGroupHiddenOnMap,
                    onLeaveTracker = { tracker ->
                        pendingConfirmAction = TrackersConfirmAction.LeaveTracker(tracker)
                    },
                    onLeaveGroup = { groupId, groupName ->
                        pendingConfirmAction = TrackersConfirmAction.LeaveGroup(
                            groupId = groupId,
                            groupName = groupName
                        )
                    },
                    onClearTrackerHistory = { trackerId, trackerName ->
                        pendingConfirmAction = TrackersConfirmAction.ClearTrackerHistory(
                            trackerId = trackerId,
                            trackerName = trackerName
                        )
                    },
                    onDeleteTracker = { trackerId, trackerName ->
                        pendingConfirmAction = TrackersConfirmAction.DeleteTracker(
                            trackerId = trackerId,
                            trackerName = trackerName
                        )
                    },
                    onDeleteGroup = { groupId, groupName ->
                        pendingConfirmAction = TrackersConfirmAction.DeleteGroup(
                            groupId = groupId,
                            groupName = groupName
                        )
                    },
                    onUnsubscribeAllGroupTracks = { group ->
                        pendingConfirmAction = TrackersConfirmAction.UnsubscribeAllGroupTracks(
                            groupId = group.id,
                            groupName = group.name,
                            trackIds = group.track_ids.orEmpty(),
                        )
                    },
                    onManageGroupTrackers = { group ->
                        groupMembershipDialog = GroupMembershipDialogState(
                            group = group,
                            selectedTrackerIds = group.track_ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        )
                    },
                    onAcceptGroup = vm::acceptGroupShare,
                    onEditTracker = vm::openEditTrackerDialog,
                    onEditGroup = vm::openEditGroupDialog,
                    navigationRequest = pendingNavigationRequest ?: localNavigationRequest,
                    onNavigationRequestHandled = {
                        pendingNavigationRequest = null
                        localNavigationRequest = null
                    },
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
                )
            }
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
        onEditTrackerVisibility = vm::updateEditTrackerVisibility,
        onEditTrackerSharedEmails = vm::updateEditTrackerSharedEmails,
        onToggleEditTrackerSharedEmail = vm::toggleEditTrackerSharedEmailSelection,
        onEditTrackerWorldShareEnabled = vm::updateEditTrackerWorldShareEnabled,
        onEditGroupDraft = vm::updateEditGroupDraft,
        onEditGroupVisibility = vm::updateEditGroupVisibility,
        onEditGroupSharedEmails = vm::updateEditGroupSharedEmails,
        onToggleEditGroupSharedEmail = vm::toggleEditGroupSharedEmailSelection,
        onEditGroupWorldShareEnabled = vm::updateEditGroupWorldShareEnabled,
        shareRecipientSuggestions = state.shareRecipientSuggestions,
        isShareRecipientSuggestionsLoading = state.isShareRecipientSuggestionsLoading,
        onSubmitCreateTracker = vm::submitCreateTracker,
        onSubmitCreateGroup = vm::submitCreateGroup,
        onSubmitEditTracker = vm::submitEditTracker,
        onSubmitEditGroup = vm::submitEditGroup,
    )

    TrackersActionConfirmDialog(
        pendingAction = pendingConfirmAction,
        onDismiss = { pendingConfirmAction = null },
        onConfirm = { action ->
            when (action) {
                is TrackersConfirmAction.LeaveTracker -> vm.leaveTracker(action.tracker)
                is TrackersConfirmAction.LeaveGroup -> vm.leaveGroup(action.groupId)
                is TrackersConfirmAction.ClearTrackerHistory -> vm.clearTrackerHistory(action.trackerId)
                is TrackersConfirmAction.DeleteTracker -> vm.deleteTracker(action.trackerId)
                is TrackersConfirmAction.DeleteGroup -> vm.deleteGroup(action.groupId)
                is TrackersConfirmAction.UnsubscribeAllGroupTracks -> {
                    vm.unsubscribeAllTracksInGroup(action.trackIds)
                }
            }
            pendingConfirmAction = null
        },
    )

    groupMembershipDialog?.let { dialogState ->
        GroupMembershipDialog(
            group = dialogState.group,
            allTrackers = state.trackers,
            selectedTrackerIds = dialogState.selectedTrackerIds,
            isApplying = state.isLoading,
            onDismiss = { groupMembershipDialog = null },
            onSelectionChanged = { nextSelected ->
                groupMembershipDialog = dialogState.copy(selectedTrackerIds = nextSelected)
            },
            onApply = { selectedIds ->
                vm.syncGroupTrackMembership(dialogState.group, selectedIds)
                groupMembershipDialog = null
            },
        )
    }
    groupActionsDialog?.let { dialog ->
        GroupMembersDialog(
            group = dialog.group,
            allTrackers = state.trackers,
            highlightedTrackerId = dialog.highlightedTrackerId,
            onDismiss = { groupActionsDialog = null },
            onViewTrackerOnMap = { tracker ->
                onOpenTrackerOnMap(tracker.id, tracker.name)
            },
            onViewTrackerParams = { tracker ->
                tracker.toTrackerParamsUiModelOrNull()?.let(onRequestTrackerParams)
            },
            onViewTrackerInList = { trackerId ->
                groupActionsDialog = null
                localNavigationRequest = TrackersHostNavigationRequest(
                    subTab = TrackersGroupsSubTab.TRACKERS,
                    trackerId = trackerId,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TrackersGroupsAuthenticatedBody(
    state: TrackersGroupsUiState,
    isServerAccessible: Boolean,
    isConnecting: Boolean,
    onSubTabSelected: (TrackersGroupsSubTab) -> Unit,
    onPullRefresh: () -> Unit,
    onToggleTrackerMapHidden: (String) -> Unit,
    onToggleGroupMapHidden: (String) -> Unit,
    onLeaveTracker: (Tracker) -> Unit,
    onLeaveGroup: (String, String) -> Unit,
    onClearTrackerHistory: (String, String) -> Unit,
    onDeleteTracker: (String, String) -> Unit,
    onDeleteGroup: (String, String) -> Unit,
    onUnsubscribeAllGroupTracks: (Group) -> Unit,
    onManageGroupTrackers: (Group) -> Unit,
    onAcceptGroup: (String) -> Unit,
    onEditTracker: (Tracker) -> Unit,
    onEditGroup: (Group) -> Unit,
    navigationRequest: TrackersHostNavigationRequest?,
    onNavigationRequestHandled: () -> Unit,
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit,
    onOpenGroupOnMap: (groupId: String) -> Unit,
    onViewTrackerParams: (Tracker) -> Unit,
    onOpenGroupActions: (group: Group, highlightedTrackerId: String?) -> Unit,
) {
    val trackersListState = rememberLazyListState()
    val groupsListState = rememberLazyListState()
    val context = LocalContext.current
    val selectedTrackerId = remember(state.trackers, state.dialog) { SelectedTrackerPrefs.selectedTrackerId(context) }
    val visibleTrackers = remember(state.trackers) { state.trackers.filter(::isVisibleOwnerTracker) }
    val visibleGroups = remember(state.groups) { state.groups.filter(::isVisibleOwnerGroup) }
    var navigationRefreshAttempts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var highlightedTrackerId by remember { mutableStateOf<String?>(null) }
    var highlightedGroupId by remember { mutableStateOf<String?>(null) }
    val tabIndex = if (state.subTab == TrackersGroupsSubTab.TRACKERS) 0 else 1
    val pagerState = rememberPagerState(initialPage = tabIndex, pageCount = { 2 })

    LaunchedEffect(state.subTab) {
        val targetPage = if (state.subTab == TrackersGroupsSubTab.TRACKERS) 0 else 1
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        val nextSubTab = if (pagerState.currentPage == 0) {
            TrackersGroupsSubTab.TRACKERS
        } else {
            TrackersGroupsSubTab.GROUPS
        }
        if (nextSubTab != state.subTab) {
            onSubTabSelected(nextSubTab)
        }
    }

    LaunchedEffect(navigationRequest, state.subTab, visibleTrackers, visibleGroups, state.isLoading, state.isPullRefreshing) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (request.subTab != state.subTab || request.focus != MapHostNavigationFocus.SCROLL_TO_ITEM) {
            if (request.subTab == state.subTab) {
                onNavigationRequestHandled()
            }
            return@LaunchedEffect
        }
        val (targetIndex, targetTrackerId, targetGroupId) = when (state.subTab) {
            TrackersGroupsSubTab.TRACKERS -> {
                val trackerId = request.trackerId
                if (trackerId.isNullOrBlank()) {
                    Triple(-1, null, null)
                } else {
                    val index = visibleTrackers.indexOfFirst { it.id == trackerId }
                    Triple(index, if (index >= 0) trackerId else null, null)
                }
            }
            TrackersGroupsSubTab.GROUPS -> {
                when {
                    !request.groupId.isNullOrBlank() -> {
                        val index = visibleGroups.indexOfFirst { it.id == request.groupId }
                        Triple(index, null, if (index >= 0) request.groupId else null)
                    }
                    !request.trackerId.isNullOrBlank() -> {
                        val index = visibleGroups.indexOfFirst { group ->
                            group.track_ids.orEmpty().any { it.trim() == request.trackerId }
                        }
                        val groupId = if (index >= 0) visibleGroups[index].id else null
                        Triple(index, null, groupId)
                    }
                    else -> Triple(-1, null, null)
                }
            }
        }
        val requestKey = request.toNavigationKey()
        if (targetIndex < 0 && (request.trackerId != null || request.groupId != null)) {
            val attempts = navigationRefreshAttempts[requestKey] ?: 0
            if (attempts == 0 && !state.isLoading && !state.isPullRefreshing) {
                navigationRefreshAttempts = navigationRefreshAttempts + (requestKey to 1)
                onPullRefresh()
                return@LaunchedEffect
            }
        }
        if (targetIndex >= 0) {
            val targetListState = if (state.subTab == TrackersGroupsSubTab.TRACKERS) {
                trackersListState
            } else {
                groupsListState
            }
            targetListState.animateScrollToItem(targetIndex)
            highlightedTrackerId = targetTrackerId
            highlightedGroupId = targetGroupId
        }
        navigationRefreshAttempts = navigationRefreshAttempts - requestKey
        onNavigationRequestHandled()
    }
    LaunchedEffect(highlightedTrackerId, highlightedGroupId) {
        if (highlightedTrackerId == null && highlightedGroupId == null) return@LaunchedEffect
        delay(1800)
        highlightedTrackerId = null
        highlightedGroupId = null
    }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.isPullRefreshing,
        onRefresh = {
            if (!state.isLoading && !state.isPullRefreshing) {
                onPullRefresh()
            }
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = tabIndex,
                backgroundColor = MaterialTheme.colors.surface,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[tabIndex]),
                        color = GeoVaultColorTokens.MainYellow,
                    )
                },
            ) {
                Tab(
                    selected = state.subTab == TrackersGroupsSubTab.TRACKERS,
                    onClick = { onSubTabSelected(TrackersGroupsSubTab.TRACKERS) },
                    selectedContentColor = GeoVaultColorTokens.TextPrimary,
                    unselectedContentColor = GeoVaultColorTokens.TextPrimary,
                    text = { Text(stringResource(R.string.trackers_subtab_trackers)) },
                )
                Tab(
                    selected = state.subTab == TrackersGroupsSubTab.GROUPS,
                    onClick = { onSubTabSelected(TrackersGroupsSubTab.GROUPS) },
                    selectedContentColor = GeoVaultColorTokens.TextPrimary,
                    unselectedContentColor = GeoVaultColorTokens.TextPrimary,
                    text = { Text(stringResource(R.string.trackers_subtab_groups)) },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pullRefresh(pullRefreshState),
            ) {
                val showBlockingLoader = state.isLoading || state.isPullRefreshing
                if (showBlockingLoader) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GeoVaultLoadingSpinner()
                            Text(
                                text = if (state.subTab == TrackersGroupsSubTab.TRACKERS) {
                                    stringResource(R.string.loading_trackers)
                                } else {
                                    stringResource(R.string.loading_groups)
                                },
                                style = MaterialTheme.typography.body2,
                            )
                        }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (page) {
                            0 -> TrackersListPage(
                                trackers = visibleTrackers,
                                mapVisibility = state.mapVisibility,
                                listState = trackersListState,
                                highlightedTrackerId = highlightedTrackerId,
                            selectedTrackerId = selectedTrackerId,
                                isLoading = state.isLoading,
                                isEnabled = !state.isLoading,
                                onToggleTrackerMapHidden = onToggleTrackerMapHidden,
                                onLeaveTracker = onLeaveTracker,
                                onEditTracker = onEditTracker,
                                onClearTrackerHistory = onClearTrackerHistory,
                                onDeleteTracker = onDeleteTracker,
                                onOpenTrackerOnMap = onOpenTrackerOnMap,
                                onViewTrackerParams = onViewTrackerParams,
                            )
                            else -> GroupsListPage(
                                groups = visibleGroups,
                                mapVisibility = state.mapVisibility,
                                listState = groupsListState,
                                highlightedGroupId = highlightedGroupId,
                                isLoading = state.isLoading,
                                isEnabled = !state.isLoading,
                                onToggleGroupMapHidden = onToggleGroupMapHidden,
                                onLeaveGroup = onLeaveGroup,
                                onAcceptGroup = onAcceptGroup,
                                onEditGroup = onEditGroup,
                                onDeleteGroup = onDeleteGroup,
                                onUnsubscribeAllGroupTracks = onUnsubscribeAllGroupTracks,
                                onManageGroupTrackers = onManageGroupTrackers,
                                onOpenGroupOnMap = onOpenGroupOnMap,
                                onOpenGroupActions = onOpenGroupActions,
                            )
                        }
                    }
                }
                if (!showBlockingLoader) {
                    PullRefreshIndicator(
                        refreshing = state.isPullRefreshing,
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        if (!isServerAccessible && !isConnecting) {
            TrackersServerFailureOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TrackersServerFailureOverlay(modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color(0xA0000000))
            .clickable(
                enabled = true,
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = MaterialTheme.shapes.medium,
            color = androidx.compose.ui.graphics.Color(0xFFFFF3F3),
            elevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.server_connection_error_title),
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                    color = GeoVaultColorTokens.Error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.server_connection_error_message),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TrackersListPage(
    trackers: List<Tracker>,
    mapVisibility: com.geovault.tracker.MapVisibilityResponse?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    highlightedTrackerId: String?,
    selectedTrackerId: String,
    isLoading: Boolean,
    isEnabled: Boolean,
    onToggleTrackerMapHidden: (String) -> Unit,
    onLeaveTracker: (Tracker) -> Unit,
    onEditTracker: (Tracker) -> Unit,
    onClearTrackerHistory: (String, String) -> Unit,
    onDeleteTracker: (String, String) -> Unit,
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit,
    onViewTrackerParams: (Tracker) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(GeoVaultColorTokens.Background),
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 88.dp),
    ) {
        if (trackers.isEmpty() && !isLoading) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.trackers_empty_trackers),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(trackers, key = { it.id }) { tracker ->
                TrackerRowCard(
                    tracker = tracker,
                    hiddenOnMap = mapVisibility?.hidden_track_ids?.contains(tracker.id) == true,
                    onToggleMapHidden = { onToggleTrackerMapHidden(tracker.id) },
                    onLeave = { onLeaveTracker(tracker) },
                    onEdit = { onEditTracker(tracker) },
                    onClearHistory = { onClearTrackerHistory(tracker.id, tracker.name) },
                    onDelete = { onDeleteTracker(tracker.id, tracker.name) },
                    onOpenMap = { onOpenTrackerOnMap(tracker.id, tracker.name) },
                    onViewParams = { onViewTrackerParams(tracker) },
                    isHighlighted = tracker.id == highlightedTrackerId,
                    isSelected = tracker.id == selectedTrackerId,
                    enabled = isEnabled,
                )
            }
        }
    }
}

@Composable
private fun GroupsListPage(
    groups: List<Group>,
    mapVisibility: com.geovault.tracker.MapVisibilityResponse?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    highlightedGroupId: String?,
    isLoading: Boolean,
    isEnabled: Boolean,
    onToggleGroupMapHidden: (String) -> Unit,
    onLeaveGroup: (String, String) -> Unit,
    onAcceptGroup: (String) -> Unit,
    onEditGroup: (Group) -> Unit,
    onDeleteGroup: (String, String) -> Unit,
    onUnsubscribeAllGroupTracks: (Group) -> Unit,
    onManageGroupTrackers: (Group) -> Unit,
    onOpenGroupOnMap: (String) -> Unit,
    onOpenGroupActions: (group: Group, highlightedTrackerId: String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(GeoVaultColorTokens.Background),
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 88.dp),
    ) {
        if (groups.isEmpty() && !isLoading) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.trackers_empty_groups),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(groups, key = { it.id }) { group ->
                GroupRowCard(
                    group = group,
                    hiddenOnMap = mapVisibility?.hidden_group_ids?.contains(group.id) == true,
                    onToggleMapHidden = { onToggleGroupMapHidden(group.id) },
                    onLeave = { onLeaveGroup(group.id, group.name) },
                    onAccept = { onAcceptGroup(group.id) },
                    onEdit = { onEditGroup(group) },
                    onDelete = { onDeleteGroup(group.id, group.name) },
                    onUnsubscribeAllTracks = { onUnsubscribeAllGroupTracks(group) },
                    onManageTrackers = { onManageGroupTrackers(group) },
                    onOpenMap = { onOpenGroupOnMap(group.id) },
                    onOpenActions = { onOpenGroupActions(group, null) },
                    isHighlighted = group.id == highlightedGroupId,
                    enabled = isEnabled,
                )
            }
        }
    }
}

private sealed interface TrackersConfirmAction {
    data class LeaveTracker(val tracker: Tracker) : TrackersConfirmAction
    data class LeaveGroup(val groupId: String, val groupName: String) : TrackersConfirmAction
    data class ClearTrackerHistory(val trackerId: String, val trackerName: String) : TrackersConfirmAction
    data class DeleteTracker(val trackerId: String, val trackerName: String) : TrackersConfirmAction
    data class DeleteGroup(val groupId: String, val groupName: String) : TrackersConfirmAction
    data class UnsubscribeAllGroupTracks(
        val groupId: String,
        val groupName: String,
        val trackIds: List<String>,
    ) : TrackersConfirmAction
}

private data class GroupMembershipDialogState(
    val group: Group,
    val selectedTrackerIds: Set<String>
)

@Composable
private fun TrackersActionConfirmDialog(
    pendingAction: TrackersConfirmAction?,
    onDismiss: () -> Unit,
    onConfirm: (TrackersConfirmAction) -> Unit,
) {
    val action = pendingAction ?: return
    val title: String
    val message: String
    val confirmLabel: String
    when (action) {
        is TrackersConfirmAction.LeaveTracker -> {
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
        is TrackersConfirmAction.LeaveGroup -> {
            title = stringResource(R.string.confirm_leave_group_title)
            message = stringResource(R.string.confirm_leave_group_message, action.groupName)
            confirmLabel = stringResource(R.string.trackers_action_leave_group)
        }
        is TrackersConfirmAction.ClearTrackerHistory -> {
            title = stringResource(R.string.confirm_clear_history_title)
            message = stringResource(R.string.confirm_clear_history_message, action.trackerName)
            confirmLabel = stringResource(R.string.trackers_action_clear_history)
        }
        is TrackersConfirmAction.DeleteTracker -> {
            title = stringResource(R.string.confirm_delete_tracker_title)
            message = stringResource(R.string.confirm_delete_tracker_message, action.trackerName)
            confirmLabel = stringResource(R.string.trackers_action_delete_tracker)
        }
        is TrackersConfirmAction.DeleteGroup -> {
            title = stringResource(R.string.confirm_delete_group_title)
            message = stringResource(R.string.confirm_delete_group_message, action.groupName)
            confirmLabel = stringResource(R.string.trackers_action_delete_group)
        }
        is TrackersConfirmAction.UnsubscribeAllGroupTracks -> {
            title = stringResource(R.string.confirm_unsubscribe_all_group_title)
            message = stringResource(
                R.string.confirm_unsubscribe_all_group_message,
                action.groupName,
                action.trackIds.size,
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
private fun TrackerRowCard(
    tracker: Tracker,
    hiddenOnMap: Boolean,
    onToggleMapHidden: () -> Unit,
    onLeave: () -> Unit,
    onEdit: () -> Unit,
    onClearHistory: () -> Unit,
    onDelete: () -> Unit,
    onOpenMap: () -> Unit,
    onViewParams: () -> Unit,
    isHighlighted: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
) {
    val canEdit = OwnershipActionPolicy.canEditTracker(tracker)
    val lastUpdateMs = tracker.lastUpdateMsOrNull()
    val lastPosition = tracker.lastPositionOrNull()
    val hasPosition = lastPosition != null
    val ownerText = tracker.owner_email?.takeIf { it.isNotBlank() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = 0.dp,
        backgroundColor = if (isHighlighted) GeoVaultColorTokens.Purple100 else GeoVaultColorTokens.Surface,
        border = BorderStroke(2.dp, GeoVaultColorTokens.PrimaryBlue),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_chevron_track),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(trackerChevronTint(tracker.color))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = tracker.name,
                    modifier = Modifier.weight(1f),
                    color = GeoVaultColorTokens.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = GeoVaultColorTokens.PrimaryBlue,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = lastUpdateMs?.let(::formatTrackerListTime)
                        ?: stringResource(R.string.waiting_for_data),
                    color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                )
                if (lastPosition != null) {
                    Text(
                        text = " • ",
                        color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = String.format(Locale.US, "%.4f, %.4f", lastPosition.first, lastPosition.second),
                        color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (ownerText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = ownerText,
                    color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GeoVaultPrimaryButton(
                    text = "Map",
                    onClick = onOpenMap,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = enabled && hasPosition,
                )
                GeoVaultSecondaryButton(
                    text = "Params",
                    onClick = onViewParams,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = enabled,
                )
                GeoVaultSecondaryButton(
                    text = "Edit",
                    onClick = onEdit,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = enabled && canEdit,
                )
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
    onDelete: () -> Unit,
    onUnsubscribeAllTracks: () -> Unit,
    onManageTrackers: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenActions: () -> Unit,
    isHighlighted: Boolean,
    enabled: Boolean,
) {
    val pending = OwnershipActionPolicy.groupPendingAccept(group)
    var menuExpanded by remember { mutableStateOf(false) }
    val ownerText = group.owner_email?.takeIf { it.isNotBlank() }
    val trackerCount = group.track_ids.orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .size
    val trackCountText = stringResource(R.string.trackers_meta_tracks_count, trackerCount)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = if (isHighlighted) GeoVaultColorTokens.Purple100 else GeoVaultColorTokens.Surface,
        border = BorderStroke(
            2.dp,
            if (pending) GeoVaultColorTokens.MainYellow else GeoVaultColorTokens.PrimaryBlue
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_groups),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(GeoVaultColorTokens.PrimaryBlue),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = enabled) { onOpenActions() }
            ) {
                Text(
                    text = group.name,
                    color = GeoVaultColorTokens.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ownerText?.let {
                    Text(
                        text = it,
                        color = GeoVaultColorTokens.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (pending) {
                        "${stringResource(R.string.trackers_badge_invite_pending)} · $trackCountText"
                    } else {
                        trackCountText
                    },
                    color = if (pending) GeoVaultColorTokens.MainYellow else GeoVaultColorTokens.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = GeoVaultColorTokens.PrimaryBlue,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (!pending) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onOpenMap()
                        }) {
                            Text(stringResource(R.string.trackers_action_view_group_on_map))
                        }
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onOpenActions()
                        }) {
                            Text(stringResource(R.string.trackers_action_view_group_members))
                        }
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onToggleMapHidden()
                        }) {
                            Text(
                                if (hiddenOnMap) {
                                    stringResource(R.string.trackers_action_show_on_map)
                                } else {
                                    stringResource(R.string.trackers_action_hide_on_map)
                                }
                            )
                        }
                    }
                    if (OwnershipActionPolicy.canEditGroup(group)) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onEdit()
                        }) {
                            Text(stringResource(R.string.trackers_action_edit))
                        }
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onManageTrackers()
                        }) {
                            Text(stringResource(R.string.trackers_action_manage_group_trackers))
                        }
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onDelete()
                        }) {
                            Text(stringResource(R.string.trackers_action_delete_group))
                        }
                    }
                    if (pending) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onAccept()
                        }) {
                            Text(stringResource(R.string.trackers_action_accept_invite))
                        }
                    } else if (!group.isOwner() && group.track_ids.orEmpty().isNotEmpty()) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onUnsubscribeAllTracks()
                        }) {
                            Text(stringResource(R.string.shared_action_unsubscribe_all_tracks))
                        }
                    } else if (OwnershipActionPolicy.groupCanLeave(group)) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onLeave()
                        }) {
                            Text(stringResource(R.string.trackers_action_leave_group))
                        }
                    }
                }
            }
        }
    }
}

private fun isVisibleOwnerTracker(tracker: Tracker): Boolean {
    val hidden = (tracker.settings?.get("hidden") as? Boolean) == true
    return tracker.isOwner() && !hidden
}

private fun trackerChevronTint(colorHex: String?): Color {
    if (colorHex.isNullOrBlank()) return GeoVaultColorTokens.PrimaryBlue
    return runCatching { Color(AndroidColor.parseColor(colorHex)) }
        .getOrElse { GeoVaultColorTokens.PrimaryBlue }
}

private fun Tracker.lastUpdateMsOrNull(): Long? {
    val coord = last_point ?: return null
    if (coord.size < 3) return null
    val value = coord[2].toLong()
    return if (value < 1_000_000_000_000L) value * 1000L else value
}

private fun Tracker.lastPositionOrNull(): Pair<Double, Double>? {
    val coord = last_point ?: return null
    if (coord.size < 2) return null
    return Pair(coord[1], coord[0])
}

private fun formatTrackerListTime(timestampMs: Long): String {
    return LIST_DATE_FORMAT.format(Date(timestampMs))
}

private fun isVisibleOwnerGroup(group: Group): Boolean {
    return group.isOwner() && group.hidden != true
}

private fun TrackersHostNavigationRequest.toNavigationKey(): String {
    return "${subTab.name}|${focus.name}|${trackerId.orEmpty()}|${groupId.orEmpty()}"
}

@Composable
private fun GroupMembershipDialog(
    group: Group,
    allTrackers: List<Tracker>,
    selectedTrackerIds: Set<String>,
    isApplying: Boolean,
    onDismiss: () -> Unit,
    onSelectionChanged: (Set<String>) -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    val knownById = allTrackers.associateBy { it.id }
    val allIds = (allTrackers.map { it.id } + selectedTrackerIds).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.trackers_dialog_manage_group_trackers_title, group.name))
        },
        text = {
            Column {
                allIds.forEach { trackerId ->
                    val trackerName = knownById[trackerId]?.name ?: trackerId
                    GeoVaultCheckmark(
                        checked = selectedTrackerIds.contains(trackerId),
                        onCheckedChange = { checked ->
                            val next = selectedTrackerIds.toMutableSet()
                            if (checked) next.add(trackerId) else next.remove(trackerId)
                            onSelectionChanged(next)
                        },
                        label = trackerName,
                        enabled = !isApplying,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(selectedTrackerIds) },
                enabled = !isApplying
            ) {
                Text(stringResource(R.string.trackers_dialog_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isApplying
            ) {
                Text(stringResource(R.string.trackers_dialog_cancel))
            }
        }
    )
}

@Composable
private fun VisibilityPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TrackerEditLoadingSurface(
    trackerName: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GeoVaultLoadingSpinner()
            Text(
                text = stringResource(
                    R.string.trackers_loading_tracker_details,
                    trackerName,
                ),
                style = MaterialTheme.typography.body2,
            )
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
    onEditTrackerVisibility: (TrackerShareVisibility) -> Unit,
    onEditTrackerSharedEmails: (String) -> Unit,
    onToggleEditTrackerSharedEmail: (String) -> Unit,
    onEditTrackerWorldShareEnabled: (Boolean) -> Unit,
    onEditGroupDraft: (String) -> Unit,
    onEditGroupVisibility: (GroupShareVisibility) -> Unit,
    onEditGroupSharedEmails: (String) -> Unit,
    onToggleEditGroupSharedEmail: (String) -> Unit,
    onEditGroupWorldShareEnabled: (Boolean) -> Unit,
    shareRecipientSuggestions: List<String>,
    isShareRecipientSuggestionsLoading: Boolean,
    onSubmitCreateTracker: () -> Unit,
    onSubmitCreateGroup: () -> Unit,
    onSubmitEditTracker: () -> Unit,
    onSubmitEditGroup: () -> Unit,
) {
    when (dialog) {
        TrackersGroupsDialog.Hidden -> Unit
        is TrackersGroupsDialog.EditTrackerLoading -> Unit
        is TrackersGroupsDialog.CreateTracker -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.trackers_dialog_create_tracker_title)) },
                text = {
                    Column {
                        GeoVaultInput(
                            value = dialog.nameDraft,
                            onValueChange = { onCreateTrackerDraft(it, dialog.colorDraft) },
                            label = stringResource(R.string.trackers_field_name),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        GeoVaultInput(
                            value = dialog.colorDraft,
                            onValueChange = { onCreateTrackerDraft(dialog.nameDraft, it) },
                            label = stringResource(R.string.trackers_field_color_optional),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        GeoVaultCheckmark(
                            checked = dialog.setAsSelectedTracker,
                            onCheckedChange = onCreateTrackerSetAsSelected,
                            label = stringResource(R.string.trackers_field_set_as_selected_tracker),
                        )
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
                    GeoVaultInput(
                        value = dialog.nameDraft,
                        onValueChange = onCreateGroupDraft,
                        label = stringResource(R.string.trackers_field_name),
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
        is TrackersGroupsDialog.EditTracker -> Unit
        is TrackersGroupsDialog.EditGroup -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.trackers_dialog_edit_group_details_title)) },
                text = {
                    Column {
                        GeoVaultInput(
                            value = dialog.nameDraft,
                            onValueChange = onEditGroupDraft,
                            label = stringResource(R.string.trackers_field_name),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.trackers_field_visibility),
                            style = MaterialTheme.typography.caption,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            VisibilityPill(
                                label = stringResource(R.string.trackers_visibility_private),
                                selected = dialog.visibilityDraft == GroupShareVisibility.PRIVATE,
                                onClick = { onEditGroupVisibility(GroupShareVisibility.PRIVATE) },
                                modifier = Modifier.weight(1f),
                            )
                            VisibilityPill(
                                label = stringResource(R.string.trackers_visibility_shared),
                                selected = dialog.visibilityDraft == GroupShareVisibility.SHARED,
                                onClick = { onEditGroupVisibility(GroupShareVisibility.SHARED) },
                                modifier = Modifier.weight(1f),
                            )
                            VisibilityPill(
                                label = stringResource(R.string.trackers_visibility_public),
                                selected = dialog.visibilityDraft == GroupShareVisibility.PUBLIC,
                                onClick = { onEditGroupVisibility(GroupShareVisibility.PUBLIC) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (dialog.visibilityDraft == GroupShareVisibility.SHARED) {
                            Spacer(modifier = Modifier.height(8.dp))
                            GeoVaultInput(
                                value = dialog.sharedEmailsDraft,
                                onValueChange = onEditGroupSharedEmails,
                                label = stringResource(R.string.trackers_field_shared_emails),
                                placeholder = stringResource(R.string.trackers_field_shared_emails_hint),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                            )
                            if (isShareRecipientSuggestionsLoading) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.trackers_share_suggestions_loading),
                                    style = MaterialTheme.typography.caption,
                                )
                            } else if (shareRecipientSuggestions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.trackers_share_suggestions_title),
                                    style = MaterialTheme.typography.caption,
                                )
                                shareRecipientSuggestions.forEach { suggestedEmail ->
                                    GeoVaultCheckmark(
                                        checked = TrackerSharingSettingsPolicy.parseSharedEmails(dialog.sharedEmailsDraft)
                                            .contains(suggestedEmail),
                                        onCheckedChange = { onToggleEditGroupSharedEmail(suggestedEmail) },
                                        label = suggestedEmail,
                                    )
                                }
                            }
                        }
                        if (dialog.visibilityDraft == GroupShareVisibility.PUBLIC) {
                            Spacer(modifier = Modifier.height(8.dp))
                            GeoVaultToggle(
                                checked = dialog.worldShareEnabledDraft,
                                onCheckedChange = onEditGroupWorldShareEnabled,
                                label = stringResource(R.string.trackers_field_world_share_enabled),
                            )
                        }
                    }
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
