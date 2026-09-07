package com.geovault.tracker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.geo.CoordinateFormat
import com.geovault.common.geo.Wgs84Point
import com.geovault.common.ui.files.GeoVaultSafExportRequest
import com.geovault.common.ui.files.rememberGeoVaultSafDocumentExportLauncher
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultEmptyState
import com.geovault.common.ui.components.GeoVaultFormDialog
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultServerConnectionFailureOverlay
import com.geovault.common.ui.components.GeoVaultFloatingActionButtonWithTooltip
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultPullRefreshLoadingContainer
import com.geovault.common.ui.components.GeoVaultRequestBottomTabsHidden
import com.geovault.common.ui.components.GeoVaultSearchField
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.components.GeoVaultTab
import com.geovault.common.ui.components.GeoVaultTabBar
import com.geovault.common.ui.components.GeoVaultToggle
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.time.GeoVaultDateTimeFormat
import com.geovault.tracker.Group
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.params.toTrackerParamsRouteArgs
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import java.util.Locale
import com.geovault.tracker.presentation.OwnershipActionPolicy
import com.geovault.tracker.presentation.GroupShareVisibility
import com.geovault.tracker.presentation.TrackerLeaveKind
import com.geovault.tracker.presentation.TrackerShareVisibility
import com.geovault.tracker.presentation.TrackerSharingSettingsPolicy
import com.geovault.tracker.presentation.TrackerKmlExportEvent
import com.geovault.tracker.presentation.TrackersListPositioningAction
import com.geovault.tracker.presentation.TrackersListPositioningInput
import com.geovault.tracker.presentation.TrackersListPositioningPolicy
import com.geovault.tracker.presentation.TrackersGroupsDialog
import com.geovault.tracker.presentation.TrackersGroupsSubTab
import com.geovault.tracker.presentation.TrackersGroupsUiState
import com.geovault.tracker.presentation.TrackersGroupsViewModel
import com.geovault.tracker.presentation.filterVisibleOwnerGroupsForSearch
import com.geovault.tracker.presentation.filterVisibleOwnerTrackersForSearch
import kotlinx.coroutines.delay

private fun formatTrackerListTime(timestampMs: Long): String {
    return GeoVaultDateTimeFormat.formatLocalDateTime(timestampMs)
}

@Immutable
private data class TrackerRowModel(
    val id: String,
    val name: String,
    val chevronColorHex: String?,
    val formattedLastUpdate: String?,
    val formattedCoordinates: String?,
    val ownerEmail: String?,
    val canEdit: Boolean,
    val hasPosition: Boolean,
    val isSelected: Boolean,
    val isHighlighted: Boolean,
    val hiddenOnMap: Boolean,
)

private sealed interface TrackerRowAction {
    data class ToggleMapHidden(val trackerId: String) : TrackerRowAction
    data class Leave(val trackerId: String) : TrackerRowAction
    data class Edit(val trackerId: String) : TrackerRowAction
    data class ClearHistory(val trackerId: String, val trackerName: String) : TrackerRowAction
    data class Delete(val trackerId: String, val trackerName: String) : TrackerRowAction
    data class OpenOnMap(val trackerId: String, val trackerName: String) : TrackerRowAction
    data class ViewParams(val trackerId: String) : TrackerRowAction
}

@Immutable
private data class GroupRowModel(
    val id: String,
    val name: String,
    val ownerEmail: String?,
    val trackerCount: Int,
    val isPending: Boolean,
    val canEdit: Boolean,
    val canLeave: Boolean,
    val isOwner: Boolean,
    val hasTrackIds: Boolean,
    val isHighlighted: Boolean,
    val hiddenOnMap: Boolean,
)

private sealed interface GroupRowAction {
    data class ToggleMapHidden(val groupId: String) : GroupRowAction
    data class Leave(val groupId: String, val groupName: String) : GroupRowAction
    data class Accept(val groupId: String) : GroupRowAction
    data class Edit(val groupId: String) : GroupRowAction
    data class Delete(val groupId: String, val groupName: String) : GroupRowAction
    data class UnsubscribeAllTracks(val groupId: String) : GroupRowAction
    data class ManageTrackers(val groupId: String) : GroupRowAction
    data class OpenOnMap(val groupId: String) : GroupRowAction
    data class OpenActions(val groupId: String) : GroupRowAction
}

private fun Tracker.toRowModel(
    hiddenTrackIds: Set<String>,
    selectedTrackerId: String,
    highlightedTrackerId: String?,
): TrackerRowModel {
    val lastUpdateMs = lastUpdateMsOrNull()
    val lastPosition = lastPositionOrNull()
    return TrackerRowModel(
        id = id,
        name = name,
        chevronColorHex = color,
        formattedLastUpdate = lastUpdateMs?.let(::formatTrackerListTime),
        formattedCoordinates = lastPosition?.let {
            CoordinateFormat.DECIMAL_4.formatLatLon(it)
        },
        ownerEmail = owner_email?.takeIf { it.isNotBlank() },
        canEdit = OwnershipActionPolicy.canEditTracker(this),
        hasPosition = lastPosition != null,
        isSelected = id == selectedTrackerId,
        isHighlighted = id == highlightedTrackerId,
        hiddenOnMap = id in hiddenTrackIds,
    )
}

private fun Group.toRowModel(
    hiddenGroupIds: Set<String>,
    highlightedGroupId: String?,
): GroupRowModel {
    return GroupRowModel(
        id = id,
        name = name,
        ownerEmail = owner_email?.takeIf { it.isNotBlank() },
        trackerCount = track_ids.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .size,
        isPending = OwnershipActionPolicy.groupPendingAccept(this),
        canEdit = OwnershipActionPolicy.canEditGroup(this),
        canLeave = OwnershipActionPolicy.groupCanLeave(this),
        isOwner = isOwner(),
        hasTrackIds = track_ids.orEmpty().isNotEmpty(),
        isHighlighted = id == highlightedGroupId,
        hiddenOnMap = id in hiddenGroupIds,
    )
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun TrackersScreen(
    vm: TrackersGroupsViewModel,
    trackersTabBottomNavStamp: Int = 0,
    auth: GeoVaultAuthShellState,
    isServerAccessible: Boolean,
    navigationRequest: TrackersHostNavigationRequest? = null,
    onNavigationTargetConsumed: () -> Unit = {},
    onOpenTrackerOnMap: (trackerId: String, trackerName: String?) -> Unit = { _, _ -> },
    onOpenGroupOnMap: (groupId: String) -> Unit = {},
    onRequestTrackerParams: (TrackerParamsRouteArgs) -> Unit = {},
    onOpenSharedListToTracker: (String) -> Unit = {},
) {
    val state by vm.uiState.collectAsState()
    DisposableEffect(vm) {
        onDispose { vm.dismissDialog() }
    }
    val context = LocalContext.current
    val launchKmlExport = rememberGeoVaultSafDocumentExportLauncher(
        mimeType = "application/vnd.google-earth.kml+xml",
        writeFailedMessage = context.getString(R.string.trackers_kml_write_failed),
    )
    LaunchedEffect(vm) {
        vm.kmlExportEvents.collect { event: TrackerKmlExportEvent ->
            launchKmlExport(
                GeoVaultSafExportRequest(
                    bytes = event.bytes,
                    suggestedFileName = "${event.fileBaseName}.kml",
                    fallbackBaseName = event.fileBaseName,
                    extensionWithoutDot = "kml",
                )
            )
        }
    }
    LaunchedEffect(vm) {
        vm.toastEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    var pendingConfirmAction by remember { mutableStateOf<TrackersConfirmAction?>(null) }
    var showOpenSettingsDiscardConfirm by remember { mutableStateOf(false) }
    var editFlowHasUnsaved by remember { mutableStateOf(false) }
    var groupMembershipDialog by remember { mutableStateOf<GroupMembershipDialogState?>(null) }
    var pendingNavigationRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var localNavigationRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var groupActionsDialog by remember { mutableStateOf<GroupMembersOverlayState?>(null) }
    var groupEditReturnOverlay by remember { mutableStateOf<GroupMembersOverlayState?>(null) }
    LaunchedEffect(trackersTabBottomNavStamp) {
        if (trackersTabBottomNavStamp == 0) return@LaunchedEffect
        groupActionsDialog = null
        groupEditReturnOverlay = null
    }
    val activeTrackerEditLoadingDialog = state.dialog as? TrackersGroupsDialog.EditTrackerLoading
    val activeTrackerEditDialog = state.dialog as? TrackersGroupsDialog.EditTracker
    val activeCreateTrackerDialog = state.dialog as? TrackersGroupsDialog.CreateTracker
    val activeGroupEditDialog = state.dialog as? TrackersGroupsDialog.EditGroup

    LaunchedEffect(navigationRequest) {
        val request = navigationRequest ?: return@LaunchedEffect
        vm.setSubTab(request.subTab)
        when (request.subTab) {
            TrackersGroupsSubTab.TRACKERS -> vm.clearTrackerSearchQuery()
            TrackersGroupsSubTab.GROUPS -> vm.clearGroupSearchQuery()
        }
        pendingNavigationRequest = request
        onNavigationTargetConsumed()
    }

    LaunchedEffect(localNavigationRequest) {
        val request = localNavigationRequest ?: return@LaunchedEffect
        vm.setSubTab(request.subTab)
        when (request.subTab) {
            TrackersGroupsSubTab.TRACKERS -> vm.clearTrackerSearchQuery()
            TrackersGroupsSubTab.GROUPS -> vm.clearGroupSearchQuery()
        }
    }

    LaunchedEffect(state.dialog) {
        if (state.dialog !is TrackersGroupsDialog.EditTracker &&
            state.dialog !is TrackersGroupsDialog.EditGroup &&
            state.dialog !is TrackersGroupsDialog.CreateTracker
        ) {
            editFlowHasUnsaved = false
        }
    }

    // Tracks whether any sub-view is rendered in [tabOverlay] right now. Drives FAB visibility;
    // Settings remains available and uses the common host-inactive path to dismiss sub-views.
    val isIntegratedSubViewOpen =
        activeTrackerEditLoadingDialog != null ||
            activeTrackerEditDialog != null ||
            activeCreateTrackerDialog != null ||
            activeGroupEditDialog != null ||
            groupMembershipDialog != null ||
            groupActionsDialog != null

    val onOpenSettingsWithEditGuard: () -> Unit = {
        if (editFlowHasUnsaved) {
            showOpenSettingsDiscardConfirm = true
        } else {
            auth.onOpenSettings()
        }
    }
    val dismissEditDialog: () -> Unit = {
        editFlowHasUnsaved = false
        vm.dismissDialog()
    }
    val dismissGroupEditDialog: () -> Unit = {
        editFlowHasUnsaved = false
        vm.dismissDialog()
    }

    LaunchedEffect(activeGroupEditDialog, groupEditReturnOverlay) {
        if (activeGroupEditDialog == null && groupEditReturnOverlay != null) {
            groupActionsDialog = groupEditReturnOverlay
            groupEditReturnOverlay = null
        }
    }

    val trackersAuth = auth.copy(onOpenSettings = onOpenSettingsWithEditGuard)
    GeoVaultTabShell(
        title = stringResource(R.string.trackers_screen_title),
        auth = trackersAuth,
        placeholderText = stringResource(R.string.trackers_placeholder_signed_out),
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        settingsMenuEnabled = true,
        authenticatedFloatingAction = {
            if (!isIntegratedSubViewOpen) {
                GeoVaultFloatingActionButtonWithTooltip(
                    onClick = {
                        if (state.subTab == TrackersGroupsSubTab.TRACKERS) {
                            vm.openCreateTrackerDialog()
                        } else {
                            vm.openCreateGroupDialog()
                        }
                    },
                    backgroundColor = GeoVaultColorTokens.MainBlue,
                    contentColor = MaterialTheme.colors.onPrimary,
                    tooltip = stringResource(R.string.tooltip_trackers_pager_fab),
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
            // List/groups body stays composed continuously; integrated sub-views (editor,
            // group actions, picker, params) all render in [tabOverlay] above the body
            // and inside the tab's chrome. The outer [GeoVaultTopTitleBar] stays visible
            // across open/close — same in-body sub-view model as the survey shell.
            TrackersGroupsAuthenticatedBody(
                state = state,
                isServerAccessible = isServerAccessible,
                isConnecting = auth.isConnecting,
                onSubTabSelected = vm::setSubTab,
                onTrackerSearchQueryChanged = vm::updateTrackerSearchQuery,
                onGroupSearchQueryChanged = vm::updateGroupSearchQuery,
                onPullRefresh = { vm.refreshAll(asPullRefresh = true) },
                onToggleTrackerMapHidden = vm::toggleTrackerHiddenOnMap,
                onToggleGroupMapHidden = vm::toggleGroupHiddenOnMap,
                onLeaveTracker = { tracker ->
                    pendingConfirmAction = TrackersConfirmAction.LeaveTracker(
                        trackerId = tracker.id,
                        trackerName = tracker.name,
                        leaveKind = OwnershipActionPolicy.trackerLeaveKind(tracker),
                    )
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
                    val ids = group.track_ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    groupMembershipDialog = GroupMembershipDialogState(
                        group = group,
                        selectedTrackerIds = ids,
                        persistedTrackerIds = ids,
                    )
                },
                onAcceptGroup = vm::acceptGroupShare,
                onEditTracker = vm::openEditTrackerDialog,
                onEditGroup = { group ->
                    groupEditReturnOverlay = null
                    vm.openEditGroupDialog(group)
                },
                navigationRequest = pendingNavigationRequest ?: localNavigationRequest,
                onNavigationRequestHandled = {
                    pendingNavigationRequest = null
                    localNavigationRequest = null
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
            // Editor sub-views render here, directly above the body (same slot as the
            // params overlay) — that keeps the outer NavTabShell title bar visible
            // across open/close. `else if` enforces mutual exclusion between overlays.
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
                                val tracker = state.trackers.firstOrNull { it.id == trackerId }
                                if (tracker?.isOwner() == true) {
                                    localNavigationRequest = TrackersHostNavigationRequest(
                                        subTab = TrackersGroupsSubTab.TRACKERS,
                                        trackerId = trackerId,
                                        focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
                                    )
                                } else {
                                    onOpenSharedListToTracker(trackerId)
                                }
                            },
                            onEditGroup = { editedGroup ->
                                groupEditReturnOverlay = dialog
                                groupActionsDialog = null
                                vm.openEditGroupDialog(editedGroup)
                            },
                            onViewGroupOnMap = { groupId ->
                                onOpenGroupOnMap(groupId)
                            },
                        )
                    }
                }
            } else if (activeCreateTrackerDialog != null) {
                val createDialog = activeCreateTrackerDialog
                Box(modifier = Modifier.fillMaxSize()) {
                    TrackerEditorScreen(
                        mode = TrackerEditorMode.Create(createDialog),
                        createBindings = TrackerEditorCreateBindings(
                            onDraftChanged = vm::updateCreateTrackerDraft,
                            onSetAsSelected = vm::updateCreateTrackerSetAsSelected,
                            onSubmit = vm::submitCreateTracker,
                        ),
                        editBindings = null,
                        shareRecipientUsers = emptyList(),
                        isShareRecipientSuggestionsLoading = false,
                        isKmlExportLoading = false,
                        isSaving = state.isLoading,
                        onDismiss = dismissEditDialog,
                        onUnsavedChangesChanged = { editFlowHasUnsaved = it },
                    )
                }
            } else if (activeTrackerEditDialog != null) {
                val editDialog = activeTrackerEditDialog
                Box(modifier = Modifier.fillMaxSize()) {
                    TrackerEditorScreen(
                        mode = TrackerEditorMode.Edit(editDialog),
                        createBindings = null,
                        editBindings = TrackerEditorEditBindings(
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
                                    trackerId = editDialog.tracker.id,
                                    trackerName = editDialog.tracker.name,
                                )
                            },
                            onDeleteTracker = {
                                pendingConfirmAction = TrackersConfirmAction.DeleteTracker(
                                    trackerId = editDialog.tracker.id,
                                    trackerName = editDialog.tracker.name,
                                )
                            },
                            onExportKml = {
                                vm.exportTrackerKml(
                                    trackerId = editDialog.tracker.id,
                                    trackerDisplayName = editDialog.nameDraft.ifBlank {
                                        editDialog.tracker.name
                                    },
                                )
                            },
                            onSubmit = vm::submitEditTracker,
                        ),
                        shareRecipientUsers = state.shareRecipientUsers,
                        isShareRecipientSuggestionsLoading = state.isShareRecipientSuggestionsLoading,
                        isKmlExportLoading = state.isKmlExportLoading,
                        isSaving = state.isLoading,
                        onDismiss = dismissEditDialog,
                        onUnsavedChangesChanged = { editFlowHasUnsaved = it },
                    )
                }
            } else if (activeTrackerEditLoadingDialog != null) {
                TrackerEditLoadingShell(
                    trackerName = activeTrackerEditLoadingDialog.trackerName,
                    onDismiss = dismissEditDialog,
                )
            } else if (groupMembershipDialog != null) {
                val dialogState = groupMembershipDialog!!
                var showPickerDiscardDialog by remember { mutableStateOf(false) }
                val hasPendingRemovals = dialogState.persistedTrackerIds.any {
                    it !in dialogState.selectedTrackerIds
                }
                val dismissPickerWithGuard: () -> Unit = {
                    if (hasPendingRemovals) {
                        showPickerDiscardDialog = true
                    } else {
                        groupMembershipDialog = null
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    GroupTrackerPickerScreen(
                        groupId = dialogState.group.id,
                        groupName = dialogState.group.name,
                        allTrackers = state.trackers,
                        selectedTrackerIds = dialogState.selectedTrackerIds,
                        isLoading = state.isPickerRefreshing,
                        addingTrackerIds = state.addingTrackerIds,
                        onRefreshTrackers = vm::refreshTrackersForPicker,
                        onSelectionChanged = { nextSelected ->
                            groupMembershipDialog = dialogState.copy(selectedTrackerIds = nextSelected)
                        },
                        onAddTracker = { trackerId ->
                            if (trackerId in dialogState.selectedTrackerIds ||
                                trackerId in dialogState.persistedTrackerIds ||
                                trackerId in state.addingTrackerIds
                            ) {
                                return@GroupTrackerPickerScreen
                            }
                            vm.addTrackerToGroup(dialogState.group.id, trackerId) {
                                groupMembershipDialog = groupMembershipDialog?.let { ds ->
                                    ds.copy(
                                        selectedTrackerIds = ds.selectedTrackerIds + trackerId,
                                        persistedTrackerIds = ds.persistedTrackerIds + trackerId,
                                    )
                                }
                            }
                        },
                        onIneligibleTrackerTap = vm::notifyReshareNotAllowed,
                        onDone = {
                            vm.syncGroupTrackMembership(
                                groupId = dialogState.group.id,
                                currentTrackerIds = dialogState.persistedTrackerIds,
                                targetTrackerIds = dialogState.selectedTrackerIds,
                            )
                            groupMembershipDialog = null
                        },
                        onDismiss = dismissPickerWithGuard,
                        onLeaveComposition = { groupMembershipDialog = null },
                        doneButtonLabel = stringResource(R.string.trackers_dialog_save),
                    )
                }
                if (showPickerDiscardDialog) {
                    GeoVaultConfirmationDialog(
                        title = stringResource(R.string.groups_edit_discard_title),
                        message = stringResource(R.string.groups_edit_discard_message),
                        onConfirm = {
                            showPickerDiscardDialog = false
                            groupMembershipDialog = null
                        },
                        onCancel = { showPickerDiscardDialog = false },
                        confirmText = stringResource(R.string.trackers_edit_discard_confirm),
                        cancelText = stringResource(R.string.trackers_dialog_cancel),
                    )
                }
            } else if (activeGroupEditDialog != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    GroupEditScreen(
                        dialog = activeGroupEditDialog,
                        allTrackers = state.trackers,
                        shareRecipientUsers = state.shareRecipientUsers,
                        isShareRecipientSuggestionsLoading = state.isShareRecipientSuggestionsLoading,
                        isPickerRefreshing = state.isPickerRefreshing,
                        addingTrackerIds = state.addingTrackerIds,
                        isSaving = state.isLoading,
                        onDismiss = dismissGroupEditDialog,
                        onReloadShareRecipients = vm::refreshShareRecipientSuggestions,
                        onRefreshTrackers = vm::refreshTrackersForPicker,
                        onNameDraftChanged = vm::updateEditGroupDraft,
                        onVisibilityChanged = vm::updateEditGroupVisibility,
                        onToggleSharedEmail = vm::toggleEditGroupSharedEmailSelection,
                        onWorldShareToggled = vm::toggleGroupWorldShare,
                        onHiddenChanged = vm::updateEditGroupHidden,
                        onUpdateDraftTrackers = vm::updateGroupDraftTrackers,
                        onAddTracker = { trackerId ->
                            if (trackerId in activeGroupEditDialog.memberTrackIds ||
                                trackerId in state.addingTrackerIds
                            ) {
                                return@GroupEditScreen
                            }
                            vm.addTrackerToGroup(activeGroupEditDialog.group.id, trackerId) {
                                vm.recordImmediateTrackerAdd(trackerId)
                            }
                        },
                        onIneligibleTrackerTap = vm::notifyReshareNotAllowed,
                        onDeleteGroup = { vm.deleteGroup(activeGroupEditDialog.group.id) },
                        onLeaveGroup = { vm.leaveGroupFromEditor(activeGroupEditDialog.group.id) },
                        onSave = vm::submitEditGroup,
                        onUnsavedChangesChanged = { editFlowHasUnsaved = it },
                    )
                }
            }
            // Params overlay always rendered last so it stacks on top of any editor when
            // both are open simultaneously (zIndex(4f) inside the layer reinforces this).
            TrackerParamsOverlayLayer()
        },
    )

    if (showOpenSettingsDiscardConfirm) {
        val isTracker = state.dialog is TrackersGroupsDialog.EditTracker ||
            state.dialog is TrackersGroupsDialog.CreateTracker
        GeoVaultConfirmationDialog(
            title = stringResource(
                if (isTracker) R.string.trackers_edit_discard_title
                else R.string.groups_edit_discard_title,
            ),
            message = stringResource(
                if (isTracker) R.string.trackers_edit_discard_message
                else R.string.groups_edit_discard_message,
            ),
            onConfirm = {
                showOpenSettingsDiscardConfirm = false
                dismissEditDialog()
                auth.onOpenSettings()
            },
            onCancel = { showOpenSettingsDiscardConfirm = false },
            confirmText = stringResource(R.string.trackers_edit_discard_confirm),
            cancelText = stringResource(R.string.trackers_dialog_cancel),
        )
    }

    TrackersGroupsDialogs(
        dialog = state.dialog,
        onDismiss = vm::dismissDialog,
        onCreateGroupDraft = vm::updateCreateGroupDraft,
        onSubmitCreateGroup = vm::submitCreateGroup,
    )

    TrackersActionConfirmDialog(
        pendingAction = pendingConfirmAction,
        onDismiss = { pendingConfirmAction = null },
        onConfirm = { action ->
            when (action) {
                is TrackersConfirmAction.LeaveTracker -> {
                    state.trackers.find { it.id == action.trackerId }?.let(vm::leaveTracker)
                }
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

}

@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TrackersGroupsAuthenticatedBody(
    state: TrackersGroupsUiState,
    isServerAccessible: Boolean,
    isConnecting: Boolean,
    onSubTabSelected: (TrackersGroupsSubTab) -> Unit,
    onTrackerSearchQueryChanged: (String) -> Unit,
    onGroupSearchQueryChanged: (String) -> Unit,
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
    val trackersListState = remember { androidx.compose.foundation.lazy.LazyListState() }
    val groupsListState = remember { androidx.compose.foundation.lazy.LazyListState() }
    val selectedTrackerId = state.selectedTrackerId
    val visibleTrackers = remember(state.trackers, state.trackerSearchQuery) {
        filterVisibleOwnerTrackersForSearch(state.trackers, state.trackerSearchQuery)
    }
    val visibleGroups = remember(state.groups, state.groupSearchQuery) {
        filterVisibleOwnerGroupsForSearch(state.groups, state.groupSearchQuery)
    }
    val orderedVisibleTrackers = visibleTrackers
    // Stable (structurally-compared) membership keys so that effects keyed on these lists do
    // not cancel/relaunch when only a tracker's last_point updated (list reference changes,
    // membership does not).
    val orderedVisibleTrackerIds = remember(orderedVisibleTrackers) {
        orderedVisibleTrackers.map { it.id }
    }
    val visibleGroupIds = remember(visibleGroups) { visibleGroups.map { it.id } }
    val latestOrderedVisibleTrackers = androidx.compose.runtime.rememberUpdatedState(orderedVisibleTrackers)
    val latestVisibleGroups = androidx.compose.runtime.rememberUpdatedState(visibleGroups)
    var navigationRefreshAttempts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var highlightedTrackerId by remember { mutableStateOf<String?>(null) }
    var highlightedGroupId by remember { mutableStateOf<String?>(null) }
    var didRequestInitialTrackersTop by remember { mutableStateOf(false) }

    val hiddenTrackIds = remember(state.mapVisibility) {
        state.mapVisibility?.hidden_track_ids.orEmpty().toSet()
    }
    val hiddenGroupIds = remember(state.mapVisibility) {
        state.mapVisibility?.hidden_group_ids.orEmpty().toSet()
    }
    val trackerModels = remember(orderedVisibleTrackers, hiddenTrackIds, selectedTrackerId, highlightedTrackerId) {
        orderedVisibleTrackers.map { it.toRowModel(hiddenTrackIds, selectedTrackerId, highlightedTrackerId) }
    }
    val groupModels = remember(visibleGroups, hiddenGroupIds, highlightedGroupId) {
        visibleGroups.map { it.toRowModel(hiddenGroupIds, highlightedGroupId) }
    }
    val shouldRequestInitialTrackersTop =
        !didRequestInitialTrackersTop &&
            state.subTab == TrackersGroupsSubTab.TRACKERS &&
            navigationRequest == null &&
            state.hasCompletedInitialLoad &&
            !state.isLoading &&
            !state.isPullRefreshing &&
            orderedVisibleTrackers.isNotEmpty()
    if (shouldRequestInitialTrackersTop) {
        // Request the initial top position before the populated LazyColumn is measured.
        // A post-frame scrollToItem fixes the position too late and is visible as a snap.
        trackersListState.requestScrollToItem(0)
    }
    SideEffect {
        if (shouldRequestInitialTrackersTop) {
            didRequestInitialTrackersTop = true
        }
    }
    val trackerLookup = remember(orderedVisibleTrackers) { orderedVisibleTrackers.associateBy { it.id } }
    val groupLookup = remember(visibleGroups) { visibleGroups.associateBy { it.id } }

    val onTrackerAction: (TrackerRowAction) -> Unit = { action ->
        when (action) {
            is TrackerRowAction.ToggleMapHidden -> onToggleTrackerMapHidden(action.trackerId)
            is TrackerRowAction.Leave -> trackerLookup[action.trackerId]?.let(onLeaveTracker)
            is TrackerRowAction.Edit -> trackerLookup[action.trackerId]?.let(onEditTracker)
            is TrackerRowAction.ClearHistory -> onClearTrackerHistory(action.trackerId, action.trackerName)
            is TrackerRowAction.Delete -> onDeleteTracker(action.trackerId, action.trackerName)
            is TrackerRowAction.OpenOnMap -> onOpenTrackerOnMap(action.trackerId, action.trackerName)
            is TrackerRowAction.ViewParams -> trackerLookup[action.trackerId]?.let(onViewTrackerParams)
        }
    }
    val onGroupAction: (GroupRowAction) -> Unit = { action ->
        when (action) {
            is GroupRowAction.ToggleMapHidden -> onToggleGroupMapHidden(action.groupId)
            is GroupRowAction.Leave -> onLeaveGroup(action.groupId, action.groupName)
            is GroupRowAction.Accept -> onAcceptGroup(action.groupId)
            is GroupRowAction.Edit -> groupLookup[action.groupId]?.let(onEditGroup)
            is GroupRowAction.Delete -> onDeleteGroup(action.groupId, action.groupName)
            is GroupRowAction.UnsubscribeAllTracks -> groupLookup[action.groupId]?.let(onUnsubscribeAllGroupTracks)
            is GroupRowAction.ManageTrackers -> groupLookup[action.groupId]?.let(onManageGroupTrackers)
            is GroupRowAction.OpenOnMap -> onOpenGroupOnMap(action.groupId)
            is GroupRowAction.OpenActions -> groupLookup[action.groupId]?.let { onOpenGroupActions(it, null) }
        }
    }
    val tabs = listOf(
        GeoVaultTab(
            value = TrackersGroupsSubTab.TRACKERS,
            label = stringResource(R.string.trackers_subtab_trackers),
        ),
        GeoVaultTab(
            value = TrackersGroupsSubTab.GROUPS,
            label = stringResource(R.string.trackers_subtab_groups),
        ),
    )
    val loadingTrackersText = stringResource(R.string.loading_trackers)
    val loadingGroupsText = stringResource(R.string.loading_groups)

    LaunchedEffect(navigationRequest, state.subTab, orderedVisibleTrackerIds, visibleGroupIds, state.isLoading, state.isPullRefreshing) {
        val request = navigationRequest
        val currentTrackers = latestOrderedVisibleTrackers.value
        val currentGroups = latestVisibleGroups.value
        val action = TrackersListPositioningPolicy.resolve(
            TrackersListPositioningInput(
                activeSubTab = state.subTab,
                isLoading = state.isLoading,
                isPullRefreshing = state.isPullRefreshing,
                navigationRequest = request,
            )
        )
        when (action) {
            TrackersListPositioningAction.NoOp -> Unit
            TrackersListPositioningAction.ConsumeWithoutScroll -> {
                request?.let { navigationRefreshAttempts = navigationRefreshAttempts - it.toNavigationKey() }
                onNavigationRequestHandled()
            }
            is TrackersListPositioningAction.ScrollToTracker -> {
                val targetIndex = currentTrackers.indexOfFirst { it.id == action.trackerId }
                if (targetIndex < 0 && request != null) {
                    if (state.isLoading || state.isPullRefreshing) {
                        return@LaunchedEffect
                    }
                    val requestKey = request.toNavigationKey()
                    val attempts = navigationRefreshAttempts[requestKey] ?: 0
                    if (attempts == 0 && !state.isLoading && !state.isPullRefreshing) {
                        navigationRefreshAttempts = navigationRefreshAttempts + (requestKey to 1)
                        onPullRefresh()
                        return@LaunchedEffect
                    }
                    navigationRefreshAttempts = navigationRefreshAttempts - requestKey
                    onNavigationRequestHandled()
                    return@LaunchedEffect
                }
                if (targetIndex >= 0) {
                    trackersListState.animateScrollToItem(targetIndex)
                    highlightedTrackerId = action.trackerId
                    highlightedGroupId = null
                }
                request?.let { navigationRefreshAttempts = navigationRefreshAttempts - it.toNavigationKey() }
                onNavigationRequestHandled()
            }
            is TrackersListPositioningAction.ScrollToGroup -> {
                val targetIndex = currentGroups.indexOfFirst { it.id == action.groupId }
                if (targetIndex < 0 && request != null) {
                    if (state.isLoading || state.isPullRefreshing) {
                        return@LaunchedEffect
                    }
                    val requestKey = request.toNavigationKey()
                    val attempts = navigationRefreshAttempts[requestKey] ?: 0
                    if (attempts == 0 && !state.isLoading && !state.isPullRefreshing) {
                        navigationRefreshAttempts = navigationRefreshAttempts + (requestKey to 1)
                        onPullRefresh()
                        return@LaunchedEffect
                    }
                    navigationRefreshAttempts = navigationRefreshAttempts - requestKey
                    onNavigationRequestHandled()
                    return@LaunchedEffect
                }
                if (targetIndex >= 0) {
                    groupsListState.animateScrollToItem(targetIndex)
                    highlightedGroupId = action.groupId
                    highlightedTrackerId = null
                }
                request?.let { navigationRefreshAttempts = navigationRefreshAttempts - it.toNavigationKey() }
                onNavigationRequestHandled()
            }
            is TrackersListPositioningAction.ScrollToGroupContainingTracker -> {
                val targetIndex = currentGroups.indexOfFirst { group ->
                    group.track_ids.orEmpty().any { it.trim() == action.trackerId }
                }
                val targetGroupId = if (targetIndex >= 0) currentGroups[targetIndex].id else null
                if (targetIndex < 0 && request != null) {
                    if (state.isLoading || state.isPullRefreshing) {
                        return@LaunchedEffect
                    }
                    val requestKey = request.toNavigationKey()
                    val attempts = navigationRefreshAttempts[requestKey] ?: 0
                    if (attempts == 0 && !state.isLoading && !state.isPullRefreshing) {
                        navigationRefreshAttempts = navigationRefreshAttempts + (requestKey to 1)
                        onPullRefresh()
                        return@LaunchedEffect
                    }
                    navigationRefreshAttempts = navigationRefreshAttempts - requestKey
                    onNavigationRequestHandled()
                    return@LaunchedEffect
                }
                if (targetIndex >= 0 && targetGroupId != null) {
                    groupsListState.animateScrollToItem(targetIndex)
                    highlightedGroupId = targetGroupId
                    highlightedTrackerId = null
                }
                request?.let { navigationRefreshAttempts = navigationRefreshAttempts - it.toNavigationKey() }
                onNavigationRequestHandled()
            }
        }
    }
    LaunchedEffect(highlightedTrackerId, highlightedGroupId) {
        if (highlightedTrackerId == null && highlightedGroupId == null) return@LaunchedEffect
        delay(1800)
        highlightedTrackerId = null
        highlightedGroupId = null
    }
    val selectedIndex = tabs.indexOfFirst { it.value == state.subTab }.let { if (it >= 0) it else 0 }
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
        if (nextTab != state.subTab) {
            onSubTabSelected(nextTab)
        }
    }
    val activeTab = tabs.getOrNull(pagerState.settledPage)?.value ?: state.subTab
    val activeLoadingText = if (activeTab == TrackersGroupsSubTab.TRACKERS) {
        loadingTrackersText
    } else {
        loadingGroupsText
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GeoVaultTabBar(
                tabs = tabs,
                selectedTab = state.subTab,
                onTabSelected = onSubTabSelected,
                indicatorPage = pagerState.currentPage,
                indicatorOffsetFraction = pagerState.currentPageOffsetFraction,
            )
            GeoVaultSearchField(
                value = if (activeTab == TrackersGroupsSubTab.TRACKERS) {
                    state.trackerSearchQuery
                } else {
                    state.groupSearchQuery
                },
                onValueChange = if (activeTab == TrackersGroupsSubTab.TRACKERS) {
                    onTrackerSearchQueryChanged
                } else {
                    onGroupSearchQueryChanged
                },
                placeholder = stringResource(R.string.trackers_search_hint),
                enabled = !state.isLoading && !state.isPullRefreshing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            GeoVaultPullRefreshLoadingContainer(
                refreshing = state.isPullRefreshing,
                showBlockingLoader = false,
                onRefresh = onPullRefresh,
                pullRefreshEnabled = true,
                showPullRefreshIndicator = !state.isLoading && !state.isPullRefreshing,
                canRefresh = !state.isLoading && !state.isPullRefreshing,
                loadingText = activeLoadingText,
                modifier = Modifier.fillMaxSize(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = true,
                    beyondViewportPageCount = 0,
                ) { page ->
                    val tab = tabs.getOrNull(page)?.value ?: return@HorizontalPager
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (tab) {
                            TrackersGroupsSubTab.TRACKERS -> TrackersListPage(
                                models = trackerModels,
                                listState = trackersListState,
                                isEmpty = orderedVisibleTrackers.isEmpty() && !state.isLoading,
                                onAction = onTrackerAction,
                                enabled = !state.isLoading,
                            )
                            TrackersGroupsSubTab.GROUPS -> GroupsListPage(
                                models = groupModels,
                                listState = groupsListState,
                                isEmpty = visibleGroups.isEmpty() && !state.isLoading,
                                onAction = onGroupAction,
                                enabled = !state.isLoading,
                            )
                        }
                        if (state.isLoading || state.isPullRefreshing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colors.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                GeoVaultLoadingSpinner(
                                    bottomText = if (tab == TrackersGroupsSubTab.TRACKERS) {
                                        loadingTrackersText
                                    } else {
                                        loadingGroupsText
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!isServerAccessible && !isConnecting) {
            GeoVaultServerConnectionFailureOverlay(
                title = stringResource(R.string.server_connection_error_title),
                message = stringResource(R.string.server_connection_error_message),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TrackersListPage(
    models: List<TrackerRowModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isEmpty: Boolean,
    onAction: (TrackerRowAction) -> Unit,
    enabled: Boolean,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 88.dp),
    ) {
        if (isEmpty) {
            item {
                Box(modifier = Modifier.fillParentMaxSize()) {
                    GeoVaultEmptyState(
                        message = stringResource(R.string.trackers_empty_trackers),
                        fillMaxSize = true,
                    )
                }
            }
        } else {
            items(models, key = { it.id }) { model ->
                TrackerRowCard(
                    model = model,
                    onAction = onAction,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun GroupsListPage(
    models: List<GroupRowModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isEmpty: Boolean,
    onAction: (GroupRowAction) -> Unit,
    enabled: Boolean,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 88.dp),
    ) {
        if (isEmpty) {
            item {
                Box(modifier = Modifier.fillParentMaxSize()) {
                    GeoVaultEmptyState(
                        message = stringResource(R.string.trackers_empty_groups),
                        fillMaxSize = true,
                    )
                }
            }
        } else {
            items(models, key = { it.id }) { model ->
                GroupRowCard(
                    model = model,
                    onAction = onAction,
                    enabled = enabled,
                )
            }
        }
    }
}

private sealed interface TrackersConfirmAction {
    data class LeaveTracker(
        val trackerId: String,
        val trackerName: String,
        val leaveKind: TrackerLeaveKind?,
    ) : TrackersConfirmAction
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
    val selectedTrackerIds: Set<String>,
    val persistedTrackerIds: Set<String>,
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
            if (action.leaveKind == TrackerLeaveKind.LeaveShare) {
                title = stringResource(R.string.confirm_leave_share_title)
                message = stringResource(R.string.confirm_leave_share_message, action.trackerName)
                confirmLabel = stringResource(R.string.trackers_action_leave_share)
            } else {
                title = stringResource(R.string.confirm_unsubscribe_title)
                message = stringResource(R.string.confirm_unsubscribe_message, action.trackerName)
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
    model: TrackerRowModel,
    onAction: (TrackerRowAction) -> Unit,
    enabled: Boolean,
) {
    com.geovault.tracker.ui.components.TrackerItemCard(
        model = com.geovault.tracker.ui.components.TrackerItemCardModel(
            title = model.name,
            chevronColorHex = model.chevronColorHex,
            lastUpdateText = model.formattedLastUpdate ?: stringResource(R.string.waiting_for_data),
            coordinatesText = model.formattedCoordinates,
            ownerEmail = model.ownerEmail,
            isHighlighted = model.isHighlighted,
            isSelected = model.isSelected,
            canOpenMap = model.hasPosition,
            canEdit = model.canEdit,
        ),
        onOpenMap = { onAction(TrackerRowAction.OpenOnMap(model.id, model.name)) },
        onViewParams = { onAction(TrackerRowAction.ViewParams(model.id)) },
        onEdit = { onAction(TrackerRowAction.Edit(model.id)) },
        enabled = enabled,
    )
}

@Composable
private fun GroupRowCard(
    model: GroupRowModel,
    onAction: (GroupRowAction) -> Unit,
    enabled: Boolean,
) {
    com.geovault.tracker.ui.components.GroupItemCard(
        model = com.geovault.tracker.ui.components.GroupItemCardModel(
            title = model.name,
            ownerEmail = model.ownerEmail,
            trackerCount = model.trackerCount,
            isPending = model.isPending,
            isHighlighted = model.isHighlighted,
            canOpenMap = !model.isPending,
            canEdit = model.canEdit,
            canOpenActions = true,
        ),
        onOpenActions = { onAction(GroupRowAction.OpenActions(model.id)) },
        onOpenMap = { onAction(GroupRowAction.OpenOnMap(model.id)) },
        onEdit = { onAction(GroupRowAction.Edit(model.id)) },
        enabled = enabled,
    )
}

private fun isVisibleOwnerTracker(tracker: Tracker): Boolean {
    val hidden = (tracker.settings?.get("hidden") as? Boolean) == true
    return tracker.isOwner() && !hidden
}

private fun Tracker.lastUpdateMsOrNull(): Long? {
    val coord = last_point ?: return null
    if (coord.size < 3) return null
    val value = coord[2].toLong()
    return if (value < 1_000_000_000_000L) value * 1000L else value
}

private fun Tracker.lastPositionOrNull(): Wgs84Point? {
    val coord = last_point ?: return null
    if (coord.size < 2) return null
    return Wgs84Point(latitude = coord[1], longitude = coord[0])
}

private fun isVisibleOwnerGroup(group: Group): Boolean {
    return group.isOwner() && group.hidden != true
}

private fun TrackersHostNavigationRequest.toNavigationKey(): String {
    return "${subTab.name}|${focus.name}|${trackerId.orEmpty()}|${groupId.orEmpty()}"
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
private fun TrackerEditLoadingShell(
    trackerName: String,
    onDismiss: () -> Unit,
) {
    GeoVaultRequestBottomTabsHidden(shouldHide = true)
    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        onBack = {
            onDismiss()
            true
        },
    )
    val borderColor =
        if (isSystemInDarkTheme()) GeoVaultColorTokens.Dark.BorderLight else GeoVaultColorTokens.BorderLight
    GeoVaultSubViewScaffold(
        modifier = Modifier.fillMaxSize(),
        title = stringResource(R.string.trackers_dialog_edit_tracker_details_title),
        onClose = onDismiss,
        // Not the same as [onDismiss]: when load finishes, this scaffold leaves composition
        // and is replaced by [TrackerEditorScreen]. `onLeaveComposition = onDismiss` would
        // treat that swap as a tab-leave and close the entire edit flow (see scaffold KDoc).
        onLeaveComposition = null,
        onHostInactive = onDismiss,
        closeContentDescription = stringResource(R.string.trackers_dialog_cancel),
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = borderColor,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                GeoVaultPrimaryButton(
                    text = stringResource(R.string.trackers_dialog_save),
                    onClick = { },
                    enabled = false,
                    tooltip = stringResource(R.string.tooltip_edit_tracker_save),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            GeoVaultLoadingSpinner(
                bottomText = stringResource(
                    R.string.trackers_loading_tracker_details,
                    trackerName,
                ),
            )
        }
    }
}

@Composable
private fun TrackersGroupsDialogs(
    dialog: TrackersGroupsDialog,
    onDismiss: () -> Unit,
    onCreateGroupDraft: (String) -> Unit,
    onSubmitCreateGroup: () -> Unit,
) {
    when (dialog) {
        TrackersGroupsDialog.Hidden -> Unit
        is TrackersGroupsDialog.EditTrackerLoading -> Unit
        is TrackersGroupsDialog.CreateTracker -> Unit
        is TrackersGroupsDialog.CreateGroup -> {
            GeoVaultFormDialog(
                title = stringResource(R.string.trackers_dialog_create_group_title),
                onConfirm = onSubmitCreateGroup,
                onDismissRequest = onDismiss,
                confirmText = stringResource(R.string.trackers_dialog_confirm_create),
                cancelText = stringResource(R.string.trackers_dialog_cancel),
            ) {
                GeoVaultInput(
                    value = dialog.nameDraft,
                    onValueChange = onCreateGroupDraft,
                    label = stringResource(R.string.trackers_field_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is TrackersGroupsDialog.EditTracker -> Unit
        is TrackersGroupsDialog.EditGroup -> Unit
    }
}
