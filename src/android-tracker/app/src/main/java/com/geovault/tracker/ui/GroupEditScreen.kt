package com.geovault.tracker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geovault.common.NaturalSort
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultFormSection
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultRequestBottomTabsDisabled
import com.geovault.common.ui.components.GeoVaultSearchableMultiSelectDialog
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultToggleHelpCard
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.UserItem
import com.geovault.tracker.presentation.GroupShareVisibility
import com.geovault.tracker.presentation.TrackerSharingSettingsPolicy
import com.geovault.tracker.presentation.TrackersGroupsDialog
import java.util.Locale

private data class GroupEditInitialSnapshot(
    val nameDraft: String,
    val visibilityDraft: GroupShareVisibility,
    val sharedEmailsDraft: String,
    val hiddenDraft: Boolean,
    val memberTrackIds: Set<String>,
)

@Composable
fun GroupEditScreen(
    dialog: TrackersGroupsDialog.EditGroup,
    allTrackers: List<Tracker>,
    shareRecipientUsers: List<UserItem>,
    isShareRecipientSuggestionsLoading: Boolean,
    isPickerRefreshing: Boolean,
    addingTrackerIds: Set<String> = emptySet(),
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onReloadShareRecipients: () -> Unit,
    onRefreshTrackers: () -> Unit,
    onNameDraftChanged: (String) -> Unit,
    onVisibilityChanged: (GroupShareVisibility) -> Unit,
    onToggleSharedEmail: (String) -> Unit,
    onWorldShareToggled: () -> Unit,
    onHiddenChanged: (Boolean) -> Unit,
    onUpdateDraftTrackers: (Set<String>) -> Unit,
    onAddTracker: (String) -> Unit = {},
    onDeleteGroup: () -> Unit,
    onLeaveGroup: () -> Unit,
    onSave: () -> Unit,
    onUnsavedChangesChanged: (Boolean) -> Unit = {},
) {
    GeoVaultRequestBottomTabsDisabled(shouldDisable = true)

    var showMembershipPicker by remember { mutableStateOf(false) }

    val initialSnapshot = remember(dialog.group.id) {
        GroupEditInitialSnapshot(
            nameDraft = dialog.nameDraft,
            visibilityDraft = dialog.visibilityDraft,
            sharedEmailsDraft = dialog.sharedEmailsDraft,
            hiddenDraft = dialog.hiddenDraft,
            memberTrackIds = dialog.memberTrackIds,
        )
    }
    val hasUnsavedChanges = remember(
        dialog.nameDraft,
        dialog.visibilityDraft,
        dialog.sharedEmailsDraft,
        dialog.hiddenDraft,
        dialog.memberTrackIds,
        initialSnapshot,
    ) {
        dialog.nameDraft != initialSnapshot.nameDraft ||
            dialog.visibilityDraft != initialSnapshot.visibilityDraft ||
            dialog.sharedEmailsDraft != initialSnapshot.sharedEmailsDraft ||
            dialog.hiddenDraft != initialSnapshot.hiddenDraft ||
            dialog.memberTrackIds != initialSnapshot.memberTrackIds
    }
    LaunchedEffect(hasUnsavedChanges) {
        onUnsavedChangesChanged(hasUnsavedChanges)
    }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val dismissWithGuard: () -> Unit = {
        if (!isSaving) {
            if (hasUnsavedChanges) {
                showDiscardDialog = true
            } else {
                onDismiss()
            }
        }
    }

    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        onBack = {
            if (showMembershipPicker) {
                showMembershipPicker = false
            } else {
                dismissWithGuard()
            }
            true
        },
    )

    if (dialog.group.isOwner()) {
        Box(modifier = Modifier.fillMaxSize()) {
            GroupEditOwnerContent(
                dialog = dialog,
                allTrackers = allTrackers,
                shareRecipientUsers = shareRecipientUsers,
                isShareRecipientSuggestionsLoading = isShareRecipientSuggestionsLoading,
                isSaving = isSaving,
                dismissWithGuard = dismissWithGuard,
                onDismiss = onDismiss,
                onReloadShareRecipients = onReloadShareRecipients,
                onNameDraftChanged = onNameDraftChanged,
                onVisibilityChanged = onVisibilityChanged,
                onToggleSharedEmail = onToggleSharedEmail,
                onWorldShareToggled = onWorldShareToggled,
                onHiddenChanged = onHiddenChanged,
                onOpenMembershipPicker = { showMembershipPicker = true },
                onDeleteGroup = onDeleteGroup,
                onSave = onSave,
                modifier = Modifier.fillMaxSize(),
            )
            if (showMembershipPicker) {
                GroupTrackerPickerScreen(
                    modifier = Modifier.fillMaxSize(),
                    groupName = dialog.group.name,
                    allTrackers = allTrackers,
                    selectedTrackerIds = dialog.memberTrackIds,
                    isLoading = isPickerRefreshing,
                    addingTrackerIds = addingTrackerIds,
                    onRefreshTrackers = onRefreshTrackers,
                    onSelectionChanged = { onUpdateDraftTrackers(it) },
                    onAddTracker = onAddTracker,
                    onDone = { showMembershipPicker = false },
                    onDismiss = { showMembershipPicker = false },
                    // Swapped away when returning to group edit — must not call outer
                    // [onDismiss] or the whole editor would close (same issue as edit-tracker
                    // loading → editor transition).
                    onLeaveComposition = null,
                )
            }
        }
    } else {
        GroupEditNonOwnerContent(
            dialog = dialog,
            onDismiss = onDismiss,
            onLeaveGroup = onLeaveGroup,
        )
    }

    if (showDiscardDialog) {
        GeoVaultConfirmationDialog(
            title = stringResource(R.string.groups_edit_discard_title),
            message = stringResource(R.string.groups_edit_discard_message),
            onConfirm = {
                showDiscardDialog = false
                onDismiss()
            },
            onCancel = { showDiscardDialog = false },
            confirmText = stringResource(R.string.trackers_edit_discard_confirm),
            cancelText = stringResource(R.string.trackers_dialog_cancel),
        )
    }
}

@Composable
private fun GroupEditOwnerContent(
    dialog: TrackersGroupsDialog.EditGroup,
    allTrackers: List<Tracker>,
    shareRecipientUsers: List<UserItem>,
    isShareRecipientSuggestionsLoading: Boolean,
    isSaving: Boolean,
    dismissWithGuard: () -> Unit,
    onDismiss: () -> Unit,
    onReloadShareRecipients: () -> Unit,
    onNameDraftChanged: (String) -> Unit,
    onVisibilityChanged: (GroupShareVisibility) -> Unit,
    onToggleSharedEmail: (String) -> Unit,
    onWorldShareToggled: () -> Unit,
    onHiddenChanged: (Boolean) -> Unit,
    onOpenMembershipPicker: () -> Unit,
    onDeleteGroup: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPickUsersDialog by remember { mutableStateOf(false) }
    var shareUserPickerSearch by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val sharedRecipientCount = TrackerSharingSettingsPolicy.parseSharedEmails(dialog.sharedEmailsDraft).size
    val destructiveAccent =
        if (isSystemInDarkTheme()) GeoVaultColorTokens.Dark.Error else GeoVaultColorTokens.Error
    val sharingSectionBackground =
        if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.Gray300.copy(alpha = 0.58f)
        } else {
            MaterialTheme.colors.onSurface.copy(alpha = 0.10f)
        }
    val borderColor = if (isSystemInDarkTheme()) GeoVaultColorTokens.Dark.BorderLight else GeoVaultColorTokens.BorderLight

    GeoVaultSubViewScaffold(
        title = stringResource(R.string.trackers_dialog_edit_group_details_title),
        onClose = dismissWithGuard,
        onLeaveComposition = onDismiss,
        modifier = modifier,
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
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                GeoVaultPrimaryButton(
                    text = stringResource(R.string.trackers_dialog_save),
                    onClick = onSave,
                    enabled = !isSaving,
                    tooltip = stringResource(R.string.tooltip_group_detail_save),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GeoVaultFormSection {
                GeoVaultInput(
                    value = dialog.nameDraft,
                    onValueChange = onNameDraftChanged,
                    label = stringResource(R.string.trackers_field_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                )
            }

            GeoVaultFormSection {
                GeoVaultSecondaryButton(
                    text = stringResource(R.string.groups_edit_members_row, dialog.memberTrackIds.size),
                    onClick = onOpenMembershipPicker,
                    enabled = !isSaving,
                    tooltip = stringResource(R.string.tooltip_group_detail_tracks_row),
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = GeoVaultColorTokens.TextSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                )
            }

            GeoVaultFormSection {
                Text(
                    text = stringResource(R.string.trackers_edit_sharing_section),
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = GeoVaultColorTokens.TextSecondary,
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 0.dp,
                    shape = RoundedCornerShape(8.dp),
                    backgroundColor = sharingSectionBackground,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                GroupEditVisibilityPill(
                                    label = stringResource(R.string.trackers_visibility_private),
                                    selected = dialog.visibilityDraft == GroupShareVisibility.PRIVATE,
                                    onClick = { onVisibilityChanged(GroupShareVisibility.PRIVATE) },
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f),
                                )
                                GroupEditVisibilityPill(
                                    label = stringResource(R.string.trackers_visibility_shared),
                                    selected = dialog.visibilityDraft == GroupShareVisibility.SHARED,
                                    onClick = { onVisibilityChanged(GroupShareVisibility.SHARED) },
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f),
                                )
                                GroupEditVisibilityPill(
                                    label = stringResource(R.string.trackers_visibility_public),
                                    selected = dialog.visibilityDraft == GroupShareVisibility.PUBLIC,
                                    onClick = { onVisibilityChanged(GroupShareVisibility.PUBLIC) },
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                text = when (dialog.visibilityDraft) {
                                    GroupShareVisibility.PRIVATE ->
                                        stringResource(R.string.groups_edit_visibility_help_private)
                                    GroupShareVisibility.SHARED ->
                                        stringResource(R.string.groups_edit_visibility_help_shared)
                                    GroupShareVisibility.PUBLIC ->
                                        stringResource(R.string.groups_edit_visibility_help_public)
                                },
                                style = MaterialTheme.typography.caption,
                                color = GeoVaultColorTokens.TextSecondary,
                            )
                        }
                        when (dialog.visibilityDraft) {
                            GroupShareVisibility.SHARED,
                            GroupShareVisibility.PUBLIC -> {
                                if (dialog.visibilityDraft == GroupShareVisibility.SHARED) {
                                    GeoVaultSecondaryButton(
                                        text = stringResource(R.string.trackers_edit_pick_users),
                                        onClick = {
                                            shareUserPickerSearch = ""
                                            onReloadShareRecipients()
                                            showPickUsersDialog = true
                                        },
                                        enabled = !isSaving,
                                        tooltip = stringResource(R.string.tooltip_sharing_pick_users),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.trackers_edit_shared_with_user_count,
                                            sharedRecipientCount,
                                            sharedRecipientCount,
                                        ),
                                        style = MaterialTheme.typography.caption,
                                        color = GeoVaultColorTokens.TextSecondary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                GeoVaultToggleHelpCard(
                                    checked = dialog.worldShareEnabledDraft,
                                    onCheckedChange = { onWorldShareToggled() },
                                    title = stringResource(R.string.trackers_field_world_share_enabled),
                                    helpText = stringResource(R.string.groups_edit_world_share_help),
                                    enabled = !isSaving && !dialog.isWorldShareLinkLoading,
                                )
                                if (dialog.worldShareEnabledDraft) {
                                    if (dialog.isWorldShareLinkLoading) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            elevation = 0.dp,
                                            border = BorderStroke(1.dp, GeoVaultColorTokens.MainBlue),
                                            backgroundColor = Color.Transparent,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                GeoVaultLoadingSpinner(spinnerSize = 20.dp)
                                            }
                                        }
                                    } else {
                                        GeoVaultSecondaryButton(
                                            text = stringResource(R.string.trackers_action_copy_world_share_link),
                                            onClick = {
                                                copyGroupWorldShareLink(context, dialog.worldShareUrlDraft)
                                            },
                                            enabled = !isSaving && !dialog.worldShareUrlDraft.isNullOrBlank(),
                                            tooltip = stringResource(R.string.tooltip_group_detail_copy_world_link),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            GroupShareVisibility.PRIVATE -> Unit
                        }
                    }
                }
            }

            GeoVaultFormSection {
                GeoVaultToggleHelpCard(
                    checked = dialog.hiddenDraft,
                    onCheckedChange = onHiddenChanged,
                    title = stringResource(R.string.groups_field_hidden),
                    helpText = stringResource(R.string.groups_edit_hidden_help),
                    enabled = !isSaving,
                )
            }

            GeoVaultFormSection {
                GeoVaultSecondaryButton(
                    text = stringResource(R.string.groups_edit_delete),
                    onClick = { showDeleteConfirm = true },
                    enabled = !isSaving,
                    accentColor = destructiveAccent,
                    tooltip = stringResource(R.string.tooltip_group_detail_delete),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showPickUsersDialog) {
        var hasSeenLoading by remember { mutableStateOf(false) }
        LaunchedEffect(isShareRecipientSuggestionsLoading) {
            if (isShareRecipientSuggestionsLoading) hasSeenLoading = true
        }
        val effectivelyLoading = isShareRecipientSuggestionsLoading || !hasSeenLoading

        GroupEditShareUserPickerDialog(
            dialog = dialog,
            shareRecipientUsers = shareRecipientUsers,
            isShareRecipientSuggestionsLoading = effectivelyLoading,
            isSaving = isSaving,
            shareUserPickerSearch = shareUserPickerSearch,
            onSearchChanged = { shareUserPickerSearch = it },
            onToggleSharedEmail = onToggleSharedEmail,
            onDismiss = { showPickUsersDialog = false },
        )
    }

    if (showDeleteConfirm) {
        GeoVaultConfirmationDialog(
            title = stringResource(R.string.confirm_delete_group_title),
            message = stringResource(R.string.confirm_delete_group_message, dialog.group.name),
            onConfirm = {
                showDeleteConfirm = false
                onDeleteGroup()
            },
            onCancel = { showDeleteConfirm = false },
            confirmText = stringResource(R.string.groups_edit_delete),
            cancelText = stringResource(R.string.trackers_dialog_cancel),
        )
    }

    if (isSaving) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GeoVaultColorTokens.ScrimMedium)
        ) {
            GeoVaultLoadingSpinner(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun GroupEditNonOwnerContent(
    dialog: TrackersGroupsDialog.EditGroup,
    onDismiss: () -> Unit,
    onLeaveGroup: () -> Unit,
) {
    var showLeaveConfirm by remember { mutableStateOf(false) }
    val destructiveAccent =
        if (isSystemInDarkTheme()) GeoVaultColorTokens.Dark.Error else GeoVaultColorTokens.Error

    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.NESTED_FULL_SCREEN_OVERLAY,
        onBack = {
            onDismiss()
            true
        },
    )

    GeoVaultSubViewScaffold(
        title = stringResource(R.string.groups_edit_shared_title),
        onClose = onDismiss,
        onLeaveComposition = onDismiss,
        closeContentDescription = stringResource(R.string.trackers_dialog_cancel),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = dialog.group.name,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                color = GeoVaultColorTokens.TextPrimary,
            )
            val ownerEmail = dialog.group.owner_email?.takeIf { it.isNotBlank() }
            if (ownerEmail != null) {
                Text(
                    text = stringResource(R.string.groups_edit_owner_label, ownerEmail),
                    style = MaterialTheme.typography.body2,
                    color = GeoVaultColorTokens.TextSecondary,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            GeoVaultSecondaryButton(
                text = stringResource(R.string.groups_edit_leave),
                onClick = { showLeaveConfirm = true },
                accentColor = destructiveAccent,
                tooltip = stringResource(R.string.tooltip_edit_shared_group_leave),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showLeaveConfirm) {
        GeoVaultConfirmationDialog(
            title = stringResource(R.string.confirm_leave_group_title),
            message = stringResource(R.string.confirm_leave_group_message, dialog.group.name),
            onConfirm = {
                showLeaveConfirm = false
                onLeaveGroup()
            },
            onCancel = { showLeaveConfirm = false },
            confirmText = stringResource(R.string.groups_edit_leave),
            cancelText = stringResource(R.string.trackers_dialog_cancel),
        )
    }
}

@Composable
private fun GroupEditShareUserPickerDialog(
    dialog: TrackersGroupsDialog.EditGroup,
    shareRecipientUsers: List<UserItem>,
    isShareRecipientSuggestionsLoading: Boolean,
    isSaving: Boolean,
    shareUserPickerSearch: String,
    onSearchChanged: (String) -> Unit,
    onToggleSharedEmail: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedEmails = TrackerSharingSettingsPolicy.parseSharedEmails(dialog.sharedEmailsDraft)
    val apiEmailsLower = shareRecipientUsers
        .map { it.email.trim().lowercase(Locale.getDefault()) }
        .filter { it.isNotEmpty() }
    val apiEmailSet = apiEmailsLower.toSet()
    val pinnedEmails = selectedEmails.filter { it !in apiEmailSet }
    val pickerRowEmails = (apiEmailsLower + pinnedEmails).distinct()
        .sortedWith(NaturalSort.naturalOrderBy { it })

    GeoVaultSearchableMultiSelectDialog(
        title = stringResource(R.string.trackers_edit_pick_users),
        hint = stringResource(R.string.trackers_edit_share_user_picker_hint),
        items = pickerRowEmails,
        isSelected = { selectedEmails.contains(it) },
        onToggleItem = onToggleSharedEmail,
        onDismiss = onDismiss,
        labelFor = { emailLower ->
            shareRecipientUsers
                .firstOrNull {
                    it.email.trim().lowercase(Locale.getDefault()) == emailLower
                }
                ?.email
                ?.trim()
                ?: emailLower
        },
        searchQuery = shareUserPickerSearch,
        onSearchQueryChange = onSearchChanged,
        searchLabel = stringResource(R.string.trackers_edit_share_user_picker_filter_label),
        searchPlaceholder = stringResource(R.string.trackers_edit_share_user_picker_filter_hint),
        emptyLabel = stringResource(R.string.trackers_no_other_users_found),
        confirmText = stringResource(R.string.trackers_edit_pick_users_done),
        isLoading = isShareRecipientSuggestionsLoading,
        loadingText = stringResource(R.string.trackers_share_suggestions_loading),
        enabled = !isSaving,
    )
}

@Composable
private fun GroupEditVisibilityPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val m = modifier.fillMaxWidth()
    if (selected) {
        GeoVaultPrimaryButton(text = label, onClick = onClick, enabled = enabled, modifier = m)
    } else {
        GeoVaultSecondaryButton(text = label, onClick = onClick, enabled = enabled, modifier = m)
    }
}

private fun copyGroupWorldShareLink(context: Context, worldShareUrl: String?) {
    if (worldShareUrl.isNullOrBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.world_share_link_clip_label), worldShareUrl)
    )
}
