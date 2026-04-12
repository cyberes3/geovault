package com.geovault.tracker.ui

import android.net.Uri
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.AlertDialog
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import com.geovault.common.ui.components.GeoVaultCheckmark
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultFloatingActionButtonWithTooltip
import com.geovault.common.ui.components.GeoVaultTab
import com.geovault.common.ui.components.GeoVaultTopTabBehavior
import com.geovault.common.ui.components.GeoVaultTopTabSurface
import com.geovault.common.ui.components.GeoVaultTopTabSwipeMode
import com.geovault.common.ui.components.GeoVaultToggle
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.Group
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.params.toTrackerParamsRouteArgs
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
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
import kotlinx.coroutines.delay

private val LIST_DATE_FORMAT = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())

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
            String.format(Locale.US, "%.4f, %.4f", it.first, it.second)
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
    onRequestTrackerParams: (TrackerParamsRouteArgs) -> Unit = {},
) {
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
    var showOpenSettingsDiscardConfirm by remember { mutableStateOf(false) }
    var editFlowHasUnsaved by remember { mutableStateOf(false) }
    var groupMembershipDialog by remember { mutableStateOf<GroupMembershipDialogState?>(null) }
    var pendingNavigationRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var localNavigationRequest by remember { mutableStateOf<TrackersHostNavigationRequest?>(null) }
    var groupActionsDialog by remember { mutableStateOf<GroupMembersOverlayState?>(null) }
    var groupEditReturnOverlay by remember { mutableStateOf<GroupMembersOverlayState?>(null) }
    val activeTrackerEditLoadingDialog = state.dialog as? TrackersGroupsDialog.EditTrackerLoading
    val activeTrackerEditDialog = state.dialog as? TrackersGroupsDialog.EditTracker
    val activeGroupEditDialog = state.dialog as? TrackersGroupsDialog.EditGroup

    LaunchedEffect(navigationRequest) {
        val request = navigationRequest ?: return@LaunchedEffect
        vm.setSubTab(request.subTab)
        pendingNavigationRequest = request
        onNavigationTargetConsumed()
    }

    LaunchedEffect(state.dialog) {
        if (state.dialog !is TrackersGroupsDialog.EditTracker && state.dialog !is TrackersGroupsDialog.EditGroup) {
            editFlowHasUnsaved = false
        }
    }

    val suppressTabTopBar = isAuthenticated && (groupMembershipDialog != null)
    val isTrackerOrGroupEditOpen =
        activeTrackerEditLoadingDialog != null ||
            activeTrackerEditDialog != null ||
            activeGroupEditDialog != null

    val onOpenSettingsWithEditGuard: () -> Unit = {
        if (editFlowHasUnsaved) {
            showOpenSettingsDiscardConfirm = true
        } else {
            onOpenSettings()
        }
    }
    val dismissEditDialog: () -> Unit = {
        editFlowHasUnsaved = false
        vm.dismissDialog()
    }
    val dismissGroupEditDialog: () -> Unit = {
        editFlowHasUnsaved = false
        vm.dismissDialog()
        groupEditReturnOverlay?.let { overlay ->
            groupActionsDialog = overlay
            groupEditReturnOverlay = null
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
        onOpenSettings = onOpenSettingsWithEditGuard,
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        connectButtonTooltip = stringResource(R.string.tooltip_settings_connect),
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        suppressTabTopBar = suppressTabTopBar,
        settingsMenuEnabled = !isTrackerOrGroupEditOpen,
        authenticatedFloatingAction = {
            if (activeTrackerEditDialog == null && activeTrackerEditLoadingDialog == null && groupActionsDialog == null && activeGroupEditDialog == null && groupMembershipDialog == null) {
                GeoVaultFloatingActionButtonWithTooltip(
                    onClick = {
                        if (state.subTab == TrackersGroupsSubTab.TRACKERS) {
                            vm.openCreateTrackerDialog()
                        } else {
                            vm.openCreateGroupDialog()
                        }
                    },
                    backgroundColor = GeoVaultColorTokens.PrimaryBlue,
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
            val renderTrackersBody: @Composable () -> Unit = {
                TrackersGroupsAuthenticatedBody(
                    state = state,
                    isServerAccessible = isServerAccessible,
                    isConnecting = isConnecting,
                    onSubTabSelected = vm::setSubTab,
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
                            group = group,
                            highlightedTrackerId = highlightedTrackerId,
                        )
                    },
                )
            }
            if (groupActionsDialog != null) {
                val dialog = groupActionsDialog!!
                Box(modifier = Modifier.fillMaxSize()) {
                    GroupActionsScreen(
                        group = dialog.group,
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
                            localNavigationRequest = TrackersHostNavigationRequest(
                                subTab = TrackersGroupsSubTab.TRACKERS,
                                trackerId = trackerId,
                                focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
                            )
                        },
                        onEditGroup = { group ->
                            groupEditReturnOverlay = dialog.copy(group = group)
                            groupActionsDialog = null
                            vm.openEditGroupDialog(group)
                        },
                        onViewGroupOnMap = { groupId ->
                            onOpenGroupOnMap(groupId)
                        },
                    )
                }
            } else if (activeTrackerEditDialog != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    renderTrackersBody()
                    TrackerEditScreen(
                        dialog = activeTrackerEditDialog,
                        shareRecipientUsers = state.shareRecipientUsers,
                        isShareRecipientSuggestionsLoading = state.isShareRecipientSuggestionsLoading,
                        isKmlExportLoading = state.isKmlExportLoading,
                        isSaving = state.isLoading,
                        onDismiss = dismissEditDialog,
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
                        onUnsavedChangesChanged = { editFlowHasUnsaved = it },
                    )
                }
            } else if (activeTrackerEditLoadingDialog != null) {
                TrackerEditLoadingSurface(
                    trackerName = activeTrackerEditLoadingDialog.trackerName,
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
                GroupTrackerPickerScreen(
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
                    onDone = {
                        vm.syncGroupTrackMembership(
                            groupId = dialogState.group.id,
                            currentTrackerIds = dialogState.persistedTrackerIds,
                            targetTrackerIds = dialogState.selectedTrackerIds,
                        )
                        groupMembershipDialog = null
                    },
                    onDismiss = dismissPickerWithGuard,
                    doneButtonLabel = stringResource(R.string.trackers_dialog_save),
                )
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
                    renderTrackersBody()
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
                        onDeleteGroup = { vm.deleteGroup(activeGroupEditDialog.group.id) },
                        onLeaveGroup = { vm.leaveGroupFromEditor(activeGroupEditDialog.group.id) },
                        onSave = vm::submitEditGroup,
                        onUnsavedChangesChanged = { editFlowHasUnsaved = it },
                    )
                }
            } else {
                renderTrackersBody()
            }
        },
    )

    if (showOpenSettingsDiscardConfirm) {
        val isTracker = state.dialog is TrackersGroupsDialog.EditTracker
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
                onOpenSettings()
            },
            onCancel = { showOpenSettingsDiscardConfirm = false },
            confirmText = stringResource(R.string.trackers_edit_discard_confirm),
            cancelText = stringResource(R.string.trackers_dialog_cancel),
        )
    }

    TrackersGroupsDialogs(
        dialog = state.dialog,
        onDismiss = vm::dismissDialog,
        onCreateTrackerDraft = vm::updateCreateTrackerDraft,
        onCreateTrackerSetAsSelected = vm::updateCreateTrackerSetAsSelected,
        onCreateGroupDraft = vm::updateCreateGroupDraft,
        onSubmitCreateTracker = vm::submitCreateTracker,
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
    val context = LocalContext.current
    val selectedTrackerId = remember(state.trackers, state.dialog) { SelectedTrackerPrefs.selectedTrackerId(context) }
    val visibleTrackers = remember(state.trackers) { state.trackers.filter(::isVisibleOwnerTracker) }
    val visibleGroups = remember(state.groups) { state.groups.filter(::isVisibleOwnerGroup) }
    val orderedVisibleTrackers = visibleTrackers
    var navigationRefreshAttempts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var highlightedTrackerId by remember { mutableStateOf<String?>(null) }
    var highlightedGroupId by remember { mutableStateOf<String?>(null) }
    var didInitialTrackersTopScroll by remember { mutableStateOf(false) }

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

    LaunchedEffect(navigationRequest, state.subTab, orderedVisibleTrackers, visibleGroups, state.isLoading, state.isPullRefreshing, didInitialTrackersTopScroll) {
        val request = navigationRequest
        val action = TrackersListPositioningPolicy.resolve(
            TrackersListPositioningInput(
                activeSubTab = state.subTab,
                isLoading = state.isLoading,
                isPullRefreshing = state.isPullRefreshing,
                hasInitializedTrackersTop = didInitialTrackersTopScroll,
                navigationRequest = request,
            )
        )
        when (action) {
            TrackersListPositioningAction.NoOp -> Unit
            TrackersListPositioningAction.ScrollToTopOnce -> {
                if (orderedVisibleTrackers.isNotEmpty()) {
                    trackersListState.scrollToItem(0)
                }
                didInitialTrackersTopScroll = true
            }
            TrackersListPositioningAction.ConsumeWithoutScroll -> {
                didInitialTrackersTopScroll = true
                request?.let { navigationRefreshAttempts = navigationRefreshAttempts - it.toNavigationKey() }
                onNavigationRequestHandled()
            }
            is TrackersListPositioningAction.ScrollToTracker -> {
                val targetIndex = orderedVisibleTrackers.indexOfFirst { it.id == action.trackerId }
                if (targetIndex < 0 && request != null) {
                    val requestKey = request.toNavigationKey()
                    val attempts = navigationRefreshAttempts[requestKey] ?: 0
                    if (attempts == 0 && !state.isLoading && !state.isPullRefreshing) {
                        navigationRefreshAttempts = navigationRefreshAttempts + (requestKey to 1)
                        onPullRefresh()
                        return@LaunchedEffect
                    }
                    navigationRefreshAttempts = navigationRefreshAttempts - requestKey
                    didInitialTrackersTopScroll = true
                    onNavigationRequestHandled()
                    return@LaunchedEffect
                }
                if (targetIndex >= 0) {
                    trackersListState.animateScrollToItem(targetIndex)
                    highlightedTrackerId = action.trackerId
                    highlightedGroupId = null
                }
                request?.let { navigationRefreshAttempts = navigationRefreshAttempts - it.toNavigationKey() }
                didInitialTrackersTopScroll = true
                onNavigationRequestHandled()
            }
            is TrackersListPositioningAction.ScrollToGroup -> {
                val targetIndex = visibleGroups.indexOfFirst { it.id == action.groupId }
                if (targetIndex < 0 && request != null) {
                    val requestKey = request.toNavigationKey()
                    val attempts = navigationRefreshAttempts[requestKey] ?: 0
                    if (attempts == 0 && !state.isLoading && !state.isPullRefreshing) {
                        navigationRefreshAttempts = navigationRefreshAttempts + (requestKey to 1)
                        onPullRefresh()
                        return@LaunchedEffect
                    }
                    navigationRefreshAttempts = navigationRefreshAttempts - requestKey
                    didInitialTrackersTopScroll = true
                    onNavigationRequestHandled()
                    return@LaunchedEffect
                }
                if (targetIndex >= 0) {
                    groupsListState.animateScrollToItem(targetIndex)
                    highlightedGroupId = action.groupId
                    highlightedTrackerId = null
                }
                request?.let { navigationRefreshAttempts = navigationRefreshAttempts - it.toNavigationKey() }
                didInitialTrackersTopScroll = true
                onNavigationRequestHandled()
            }
            is TrackersListPositioningAction.ScrollToGroupContainingTracker -> {
                val targetIndex = visibleGroups.indexOfFirst { group ->
                    group.track_ids.orEmpty().any { it.trim() == action.trackerId }
                }
                val targetGroupId = if (targetIndex >= 0) visibleGroups[targetIndex].id else null
                if (targetIndex < 0 && request != null) {
                    val requestKey = request.toNavigationKey()
                    val attempts = navigationRefreshAttempts[requestKey] ?: 0
                    if (attempts == 0 && !state.isLoading && !state.isPullRefreshing) {
                        navigationRefreshAttempts = navigationRefreshAttempts + (requestKey to 1)
                        onPullRefresh()
                        return@LaunchedEffect
                    }
                    navigationRefreshAttempts = navigationRefreshAttempts - requestKey
                    didInitialTrackersTopScroll = true
                    onNavigationRequestHandled()
                    return@LaunchedEffect
                }
                if (targetIndex >= 0 && targetGroupId != null) {
                    groupsListState.animateScrollToItem(targetIndex)
                    highlightedGroupId = targetGroupId
                    highlightedTrackerId = null
                }
                request?.let { navigationRefreshAttempts = navigationRefreshAttempts - it.toNavigationKey() }
                didInitialTrackersTopScroll = true
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
    Box(modifier = Modifier.fillMaxSize()) {
        GeoVaultTopTabSurface(
            tabs = tabs,
            selectedTab = state.subTab,
            onTabSelected = onSubTabSelected,
            behavior = GeoVaultTopTabBehavior(
                swipeMode = GeoVaultTopTabSwipeMode.ALWAYS,
                isTabRefreshing = { state.isPullRefreshing },
                isTabBlocking = { state.isLoading || state.isPullRefreshing },
                canRefreshTab = { !state.isLoading && !state.isPullRefreshing },
                isPullRefreshEnabled = { true },
                loadingTextForTab = { tab -> if (tab == TrackersGroupsSubTab.TRACKERS) loadingTrackersText else loadingGroupsText },
                onRefreshTab = { onPullRefresh() },
            ),
            contentForTab = { tab ->
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
            },
        )
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
    models: List<TrackerRowModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isEmpty: Boolean,
    onAction: (TrackerRowAction) -> Unit,
    enabled: Boolean,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(GeoVaultColorTokens.Background),
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 88.dp),
    ) {
        if (isEmpty) {
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
            .background(GeoVaultColorTokens.Background),
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 88.dp),
    ) {
        if (isEmpty) {
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
        GeoVaultLoadingSpinner(
            bottomText = stringResource(
                R.string.trackers_loading_tracker_details,
                trackerName,
            ),
        )
    }
}

@Composable
private fun TrackersGroupsDialogs(
    dialog: TrackersGroupsDialog,
    onDismiss: () -> Unit,
    onCreateTrackerDraft: (String, String) -> Unit,
    onCreateTrackerSetAsSelected: (Boolean) -> Unit,
    onCreateGroupDraft: (String) -> Unit,
    onSubmitCreateTracker: () -> Unit,
    onSubmitCreateGroup: () -> Unit,
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
        is TrackersGroupsDialog.EditGroup -> Unit
    }
}
