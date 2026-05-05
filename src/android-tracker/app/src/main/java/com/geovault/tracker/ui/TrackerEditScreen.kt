package com.geovault.tracker.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Icon
import com.geovault.common.ui.components.GeoVaultClickableWithTooltip
import com.geovault.common.ui.components.GeoVaultIconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ClipboardCopyHelper
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultSelectField
import com.geovault.common.ui.components.GeoVaultFormSection
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSearchableMultiSelectDialog
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultSelectOption
import com.geovault.common.ui.components.GeoVaultToggleHelpCard
import com.geovault.common.ui.components.GeoVaultRequestBottomTabsDisabled
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.NaturalSort
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.tracker.R
import com.geovault.tracker.UserItem
import com.geovault.tracker.TrackerRecentDataWindowOptions
import com.geovault.tracker.parseHexToColorInt
import com.geovault.tracker.showHueColorPickerDialog
import com.geovault.tracker.presentation.TrackersGroupsDialog
import com.geovault.tracker.presentation.TrackerShareVisibility
import com.geovault.tracker.presentation.TrackerSharingSettingsPolicy
import com.geovault.tracker.services.TrackingRuntimeStateStore
import java.util.Locale

/** Default color shown when opening the create flow; must stay in sync with [TrackersGroupsViewModel.openCreateTrackerDialog]. */
private val TrackerCreateDefaultColorHex = GeoVaultColorTokens.Hex.Blue400

sealed interface TrackerEditorMode {
    data class Create(val dialog: TrackersGroupsDialog.CreateTracker) : TrackerEditorMode
    data class Edit(val dialog: TrackersGroupsDialog.EditTracker) : TrackerEditorMode
}

data class TrackerEditorCreateBindings(
    val onDraftChanged: (String, String) -> Unit,
    val onSetAsSelected: (Boolean) -> Unit,
    val onSubmit: () -> Unit,
)

data class TrackerEditorEditBindings(
    val onReloadShareRecipients: () -> Unit,
    val onNameDraftChanged: (String) -> Unit,
    val onColorDraftChanged: (String) -> Unit,
    val onSetAsSelectedChanged: (Boolean) -> Unit,
    val onHiddenChanged: (Boolean) -> Unit,
    val onRecentDataWindowChanged: (String) -> Unit,
    val onVisibilityChanged: (TrackerShareVisibility) -> Unit,
    val onShareParamsWithRecipientsChanged: (Boolean) -> Unit,
    val onAllowGroupReshareChanged: (Boolean) -> Unit,
    val onToggleSharedEmail: (String) -> Unit,
    val onWorldShareEnabledChanged: (Boolean) -> Unit,
    val onShareParamsWithWorldChanged: (Boolean) -> Unit,
    val onClearHistory: () -> Unit,
    val onDeleteTracker: () -> Unit,
    val onExportKml: () -> Unit,
    val onSubmit: () -> Unit,
)

private data class TrackerEditInitialSnapshot(
    val nameDraft: String,
    val colorDraft: String,
    val setAsSelectedTracker: Boolean,
    val hiddenDraft: Boolean,
    val recentDataWindowDraft: String,
    val visibilityDraft: TrackerShareVisibility,
    val sharedEmailsDraft: String,
    val shareParamsWithRecipientsDraft: Boolean,
    val allowGroupReshareDraft: Boolean,
    val worldShareEnabledDraft: Boolean,
    val shareParamsWithWorldDraft: Boolean,
)

@Composable
fun TrackerEditorScreen(
    mode: TrackerEditorMode,
    createBindings: TrackerEditorCreateBindings?,
    editBindings: TrackerEditorEditBindings?,
    shareRecipientUsers: List<UserItem>,
    isShareRecipientSuggestionsLoading: Boolean,
    isKmlExportLoading: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onUnsavedChangesChanged: (Boolean) -> Unit = {},
) {
    GeoVaultRequestBottomTabsDisabled(shouldDisable = true)

    val context = LocalContext.current
    var showDiscardDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges = rememberTrackerEditorHasUnsavedChanges(mode)

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
            dismissWithGuard()
            true
        },
    )

    LaunchedEffect(hasUnsavedChanges) {
        onUnsavedChangesChanged(hasUnsavedChanges)
    }

    val destructiveAccent =
        if (isSystemInDarkTheme()) GeoVaultColorTokens.Dark.Error else GeoVaultColorTokens.Error
    val sharingSectionBackground =
        if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.Gray300.copy(alpha = 0.58f)
        } else {
            MaterialTheme.colors.onSurface.copy(alpha = 0.10f)
        }

    val (title, primaryLabel, primaryTooltip, onPrimary) = when (mode) {
        is TrackerEditorMode.Create -> {
            val c = checkNotNull(createBindings) { "Create bindings required for Create mode" }
            Quadruple(
                stringResource(R.string.trackers_dialog_create_tracker_title),
                stringResource(R.string.trackers_dialog_confirm_create),
                stringResource(R.string.tooltip_create_tracker_submit),
                c.onSubmit,
            )
        }
        is TrackerEditorMode.Edit -> {
            val e = checkNotNull(editBindings) { "Edit bindings required for Edit mode" }
            Quadruple(
                stringResource(R.string.trackers_dialog_edit_tracker_details_title),
                stringResource(R.string.trackers_dialog_save),
                stringResource(R.string.tooltip_edit_tracker_save),
                e.onSubmit,
            )
        }
    }

    GeoVaultSubViewScaffold(
        title = title,
        onClose = dismissWithGuard,
        onLeaveComposition = onDismiss,
        closeContentDescription = stringResource(R.string.trackers_dialog_cancel),
        bottomBar = {
            val borderColor = if (isSystemInDarkTheme()) GeoVaultColorTokens.Dark.BorderLight else GeoVaultColorTokens.BorderLight
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
                    text = primaryLabel,
                    onClick = onPrimary,
                    enabled = !isSaving,
                    tooltip = primaryTooltip,
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
            when (mode) {
                is TrackerEditorMode.Create -> {
                    val c = checkNotNull(createBindings)
                    val runtime by TrackingRuntimeStateStore.state.collectAsState()
                    TrackerCreateFormContent(
                        dialog = mode.dialog,
                        isSaving = isSaving,
                        defaultSelectedTrackerEnabled = !runtime.localRecordingActive,
                        onDraftChanged = c.onDraftChanged,
                        onSetAsSelectedChanged = c.onSetAsSelected,
                    )
                }
                is TrackerEditorMode.Edit -> {
                    val dialog = mode.dialog
                    val e = checkNotNull(editBindings)
                    TrackerEditFormContent(
                        dialog = dialog,
                        isSaving = isSaving,
                        isKmlExportLoading = isKmlExportLoading,
                        destructiveAccent = destructiveAccent,
                        sharingSectionBackground = sharingSectionBackground,
                        shareRecipientUsers = shareRecipientUsers,
                        isShareRecipientSuggestionsLoading = isShareRecipientSuggestionsLoading,
                        onReloadShareRecipients = e.onReloadShareRecipients,
                        onNameDraftChanged = e.onNameDraftChanged,
                        onColorDraftChanged = e.onColorDraftChanged,
                        onSetAsSelectedChanged = e.onSetAsSelectedChanged,
                        onHiddenChanged = e.onHiddenChanged,
                        onRecentDataWindowChanged = e.onRecentDataWindowChanged,
                        onVisibilityChanged = e.onVisibilityChanged,
                        onShareParamsWithRecipientsChanged = e.onShareParamsWithRecipientsChanged,
                        onAllowGroupReshareChanged = e.onAllowGroupReshareChanged,
                        onWorldShareEnabledChanged = e.onWorldShareEnabledChanged,
                        onShareParamsWithWorldChanged = e.onShareParamsWithWorldChanged,
                        onClearHistory = e.onClearHistory,
                        onDeleteTracker = e.onDeleteTracker,
                        onExportKml = e.onExportKml,
                        onToggleSharedEmail = e.onToggleSharedEmail,
                    )
                }
            }
        }
    }

    if (showDiscardDialog) {
        GeoVaultConfirmationDialog(
            title = stringResource(R.string.trackers_edit_discard_title),
            message = stringResource(R.string.trackers_edit_discard_message),
            onConfirm = {
                showDiscardDialog = false
                onDismiss()
            },
            onCancel = { showDiscardDialog = false },
            confirmText = stringResource(R.string.trackers_edit_discard_confirm),
            cancelText = stringResource(R.string.trackers_dialog_cancel),
        )
    }

    if (isSaving) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GeoVaultColorTokens.ScrimMedium),
        ) {
            GeoVaultLoadingSpinner(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

private fun normalizeHexForCompare(raw: String): String {
    val t = raw.trim().ifEmpty { return "" }
    val withHash = if (t.startsWith("#")) t else "#$t"
    return withHash.lowercase(Locale.getDefault())
}

private fun createTrackerFormIsDirty(d: TrackersGroupsDialog.CreateTracker): Boolean {
    return d.nameDraft.isNotBlank() ||
        normalizeHexForCompare(d.colorDraft) != normalizeHexForCompare(TrackerCreateDefaultColorHex) ||
        d.setAsSelectedTracker
}

@Composable
private fun rememberTrackerEditorHasUnsavedChanges(mode: TrackerEditorMode): Boolean {
    return when (mode) {
        is TrackerEditorMode.Create -> createTrackerFormIsDirty(mode.dialog)
        is TrackerEditorMode.Edit -> {
            val dialog = mode.dialog
            val initialSnapshot = remember(dialog.tracker.id) {
                TrackerEditInitialSnapshot(
                    nameDraft = dialog.nameDraft,
                    colorDraft = dialog.colorDraft,
                    setAsSelectedTracker = dialog.setAsSelectedTracker,
                    hiddenDraft = dialog.hiddenDraft,
                    recentDataWindowDraft = dialog.recentDataWindowDraft,
                    visibilityDraft = dialog.visibilityDraft,
                    sharedEmailsDraft = dialog.sharedEmailsDraft,
                    shareParamsWithRecipientsDraft = dialog.shareParamsWithRecipientsDraft,
                    allowGroupReshareDraft = dialog.allowGroupReshareDraft,
                    worldShareEnabledDraft = dialog.worldShareEnabledDraft,
                    shareParamsWithWorldDraft = dialog.shareParamsWithWorldDraft,
                )
            }
            remember(
                dialog.nameDraft,
                dialog.colorDraft,
                dialog.setAsSelectedTracker,
                dialog.hiddenDraft,
                dialog.recentDataWindowDraft,
                dialog.visibilityDraft,
                dialog.sharedEmailsDraft,
                dialog.shareParamsWithRecipientsDraft,
                dialog.allowGroupReshareDraft,
                dialog.worldShareEnabledDraft,
                dialog.shareParamsWithWorldDraft,
                initialSnapshot,
            ) {
                dialog.nameDraft != initialSnapshot.nameDraft ||
                    dialog.colorDraft != initialSnapshot.colorDraft ||
                    dialog.setAsSelectedTracker != initialSnapshot.setAsSelectedTracker ||
                    dialog.hiddenDraft != initialSnapshot.hiddenDraft ||
                    dialog.recentDataWindowDraft != initialSnapshot.recentDataWindowDraft ||
                    dialog.visibilityDraft != initialSnapshot.visibilityDraft ||
                    dialog.sharedEmailsDraft != initialSnapshot.sharedEmailsDraft ||
                    dialog.shareParamsWithRecipientsDraft != initialSnapshot.shareParamsWithRecipientsDraft ||
                    dialog.allowGroupReshareDraft != initialSnapshot.allowGroupReshareDraft ||
                    dialog.worldShareEnabledDraft != initialSnapshot.worldShareEnabledDraft ||
                    dialog.shareParamsWithWorldDraft != initialSnapshot.shareParamsWithWorldDraft
            }
        }
    }
}

@Composable
private fun TrackerCreateFormContent(
    dialog: TrackersGroupsDialog.CreateTracker,
    isSaving: Boolean,
    defaultSelectedTrackerEnabled: Boolean,
    onDraftChanged: (String, String) -> Unit,
    onSetAsSelectedChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val clipboardHelper = remember(context) { ClipboardCopyHelper(context) }
    val colorPreview = remember(dialog.colorDraft, context) {
        resolveTrackerColorPreview(dialog.colorDraft)
    }
    GeoVaultFormSection {
        GeoVaultInput(
            value = dialog.nameDraft,
            onValueChange = { onDraftChanged(it, dialog.colorDraft) },
            label = stringResource(R.string.trackers_field_name),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving,
        )
        Text(
            text = stringResource(R.string.trackers_field_color_optional),
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val openColorPicker = {
                showHueColorPickerDialog(
                    context = context,
                    initialHex = dialog.colorDraft.ifBlank { null },
                    onColorPicked = { hex -> onDraftChanged(dialog.nameDraft, hex) },
                )
            }
            GeoVaultClickableWithTooltip(
                onClick = openColorPicker,
                enabled = !isSaving,
                tooltip = stringResource(R.string.tooltip_edit_tracker_pick_color),
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colorPreview.backgroundColor),
            ) {
                if (colorPreview.showInvalidBadge) {
                    Text(
                        text = "!",
                        color = MaterialTheme.colors.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                }
            }
            GeoVaultIconButton(
                onClick = openColorPicker,
                enabled = !isSaving,
                tooltip = stringResource(R.string.tooltip_edit_tracker_pick_color),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_eye_dropper),
                    contentDescription = stringResource(R.string.trackers_action_pick_tracker_color),
                    tint = GeoVaultColorTokens.MainBlue,
                    modifier = Modifier.size(24.dp),
                )
            }
            GeoVaultInput(
                value = dialog.colorDraft,
                onValueChange = { onDraftChanged(dialog.nameDraft, it) },
                label = null,
                singleLine = true,
                placeholder = stringResource(R.string.trackers_field_color_hint),
                modifier = Modifier.weight(1f),
                enabled = !isSaving,
            )
        }
        GeoVaultToggleHelpCard(
            checked = dialog.setAsSelectedTracker,
            onCheckedChange = onSetAsSelectedChanged,
            title = stringResource(R.string.trackers_edit_default_track_title),
            helpText = stringResource(R.string.trackers_edit_default_track_help),
            enabled = !isSaving && defaultSelectedTrackerEnabled,
        )
    }
}

@Composable
private fun TrackerEditFormContent(
    dialog: TrackersGroupsDialog.EditTracker,
    isSaving: Boolean,
    isKmlExportLoading: Boolean,
    destructiveAccent: Color,
    sharingSectionBackground: Color,
    shareRecipientUsers: List<UserItem>,
    isShareRecipientSuggestionsLoading: Boolean,
    onReloadShareRecipients: () -> Unit,
    onNameDraftChanged: (String) -> Unit,
    onColorDraftChanged: (String) -> Unit,
    onSetAsSelectedChanged: (Boolean) -> Unit,
    onHiddenChanged: (Boolean) -> Unit,
    onRecentDataWindowChanged: (String) -> Unit,
    onVisibilityChanged: (TrackerShareVisibility) -> Unit,
    onShareParamsWithRecipientsChanged: (Boolean) -> Unit,
    onAllowGroupReshareChanged: (Boolean) -> Unit,
    onWorldShareEnabledChanged: (Boolean) -> Unit,
    onShareParamsWithWorldChanged: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteTracker: () -> Unit,
    onExportKml: () -> Unit,
    onToggleSharedEmail: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboardHelper = remember(context) { ClipboardCopyHelper(context) }
    val colorPreview = remember(dialog.colorDraft, context) {
        resolveTrackerColorPreview(dialog.colorDraft)
    }
    val recentOptions = remember(context) {
        TrackerRecentDataWindowOptions.labels(context).mapIndexed { index, label ->
            GeoVaultSelectOption(
                value = TrackerRecentDataWindowOptions.valueForIndex(index),
                label = label,
            )
        }
    }
    var showPickUsersDialog by remember { mutableStateOf(false) }
    var shareUserPickerSearch by remember { mutableStateOf("") }
    val sharedRecipientCount = TrackerSharingSettingsPolicy.parseSharedEmails(dialog.sharedEmailsDraft).size

    GeoVaultFormSection {
        GeoVaultInput(
            value = dialog.nameDraft,
            onValueChange = onNameDraftChanged,
            label = stringResource(R.string.trackers_field_name),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving,
        )
        Text(
            text = stringResource(R.string.trackers_field_color_optional),
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val openColorPicker = {
                showHueColorPickerDialog(
                    context = context,
                    initialHex = dialog.colorDraft.ifBlank { null },
                    onColorPicked = onColorDraftChanged,
                )
            }
            GeoVaultClickableWithTooltip(
                onClick = openColorPicker,
                enabled = !isSaving,
                tooltip = stringResource(R.string.tooltip_edit_tracker_pick_color),
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colorPreview.backgroundColor),
            ) {
                if (colorPreview.showInvalidBadge) {
                    Text(
                        text = "!",
                        color = MaterialTheme.colors.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                }
            }
            GeoVaultIconButton(
                onClick = openColorPicker,
                enabled = !isSaving,
                tooltip = stringResource(R.string.tooltip_edit_tracker_pick_color),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_eye_dropper),
                    contentDescription = stringResource(R.string.trackers_action_pick_tracker_color),
                    tint = GeoVaultColorTokens.MainBlue,
                    modifier = Modifier.size(24.dp),
                )
            }
            GeoVaultInput(
                value = dialog.colorDraft,
                onValueChange = onColorDraftChanged,
                label = null,
                singleLine = true,
                placeholder = stringResource(R.string.trackers_field_color_hint),
                modifier = Modifier.weight(1f),
                enabled = !isSaving,
            )
        }
        GeoVaultToggleHelpCard(
            checked = dialog.setAsSelectedTracker,
            onCheckedChange = onSetAsSelectedChanged,
            title = stringResource(R.string.trackers_edit_default_track_title),
            helpText = stringResource(R.string.trackers_edit_default_track_help),
            enabled = !isSaving,
        )
        GeoVaultToggleHelpCard(
            checked = dialog.hiddenDraft,
            onCheckedChange = onHiddenChanged,
            title = stringResource(R.string.trackers_field_hidden_on_map),
            helpText = stringResource(R.string.trackers_edit_hidden_help),
            enabled = !isSaving && !dialog.setAsSelectedTracker,
        )
        GeoVaultSelectField(
            label = stringResource(R.string.trackers_edit_recent_data_filter_title),
            dialogTitle = stringResource(R.string.trackers_edit_recent_data_filter_title),
            selectedValue = dialog.recentDataWindowDraft,
            options = recentOptions,
            onValueSelected = onRecentDataWindowChanged,
            enabled = !isSaving,
        )
        Text(
            text = stringResource(R.string.trackers_edit_recent_data_help),
            style = MaterialTheme.typography.caption,
            color = geoVaultContentSecondaryColor(),
        )
    }

    if (dialog.tracker.isOwner()) {
        GeoVaultFormSection {
            Text(
                text = stringResource(R.string.trackers_edit_sharing_section),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                color = geoVaultContentSecondaryColor(),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 0.dp,
                shape = RoundedCornerShape(8.dp),
                backgroundColor = sharingSectionBackground,
            ) {
                Box {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                EditVisibilityPill(
                                    label = stringResource(R.string.trackers_visibility_private),
                                    selected = dialog.visibilityDraft == TrackerShareVisibility.PRIVATE,
                                    onClick = { onVisibilityChanged(TrackerShareVisibility.PRIVATE) },
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f),
                                )
                                EditVisibilityPill(
                                    label = stringResource(R.string.trackers_visibility_shared),
                                    selected = dialog.visibilityDraft == TrackerShareVisibility.SHARED,
                                    onClick = { onVisibilityChanged(TrackerShareVisibility.SHARED) },
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f),
                                )
                                EditVisibilityPill(
                                    label = stringResource(R.string.trackers_visibility_public),
                                    selected = dialog.visibilityDraft == TrackerShareVisibility.PUBLIC,
                                    onClick = { onVisibilityChanged(TrackerShareVisibility.PUBLIC) },
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                text = when (dialog.visibilityDraft) {
                                    TrackerShareVisibility.PRIVATE ->
                                        stringResource(R.string.trackers_edit_visibility_help_private)
                                    TrackerShareVisibility.SHARED ->
                                        stringResource(R.string.trackers_edit_visibility_help_shared)
                                    TrackerShareVisibility.PUBLIC ->
                                        stringResource(R.string.trackers_edit_visibility_help_public)
                                },
                                style = MaterialTheme.typography.caption,
                                color = geoVaultContentSecondaryColor(),
                            )
                        }
                        when (dialog.visibilityDraft) {
                            TrackerShareVisibility.SHARED,
                            TrackerShareVisibility.PUBLIC -> {
                                if (dialog.visibilityDraft == TrackerShareVisibility.SHARED) {
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
                                        color = geoVaultContentSecondaryColor(),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    GeoVaultToggleHelpCard(
                                        checked = dialog.shareParamsWithRecipientsDraft,
                                        onCheckedChange = onShareParamsWithRecipientsChanged,
                                        title = stringResource(R.string.trackers_field_share_params_with_recipients),
                                        helpText = stringResource(R.string.trackers_edit_share_params_recipients_help),
                                        enabled = !isSaving,
                                    )
                                    GeoVaultToggleHelpCard(
                                        checked = dialog.allowGroupReshareDraft,
                                        onCheckedChange = onAllowGroupReshareChanged,
                                        title = stringResource(R.string.trackers_field_allow_group_reshare),
                                        helpText = stringResource(R.string.trackers_edit_allow_groups_help),
                                        enabled = !isSaving,
                                    )
                                }
                                GeoVaultToggleHelpCard(
                                    checked = dialog.worldShareEnabledDraft,
                                    onCheckedChange = onWorldShareEnabledChanged,
                                    title = stringResource(R.string.trackers_field_world_share_enabled),
                                    helpText = stringResource(R.string.trackers_edit_world_share_help),
                                    enabled = !isSaving && !dialog.isWorldShareLinkLoading,
                                )
                                InternalShareLinkCopySection(
                                    helpText = stringResource(R.string.trackers_edit_internal_share_help),
                                    shareUrl = dialog.internalShareUrlDraft,
                                    enabled = !isSaving && !dialog.internalShareUrlDraft.isNullOrBlank(),
                                    tooltip = stringResource(R.string.tooltip_edit_tracker_copy_internal_link),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (dialog.worldShareEnabledDraft) {
                                    GeoVaultToggleHelpCard(
                                        checked = dialog.shareParamsWithWorldDraft,
                                        onCheckedChange = onShareParamsWithWorldChanged,
                                        title = stringResource(R.string.trackers_field_share_params_with_world),
                                        helpText = stringResource(R.string.trackers_edit_share_params_world_help),
                                        enabled = !isSaving && !dialog.isWorldShareLinkLoading,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        GeoVaultSecondaryButton(
                                            text = stringResource(R.string.trackers_action_copy_world_share_link),
                                            onClick = {
                                                copyShareLink(
                                                    context = context,
                                                    clipboardHelper = clipboardHelper,
                                                    shareUrl = dialog.worldShareUrlDraft,
                                                    label = context.getString(R.string.world_share_link_clip_label),
                                                )
                                            },
                                            enabled = !isSaving && !dialog.worldShareUrlDraft.isNullOrBlank(),
                                            tooltip = stringResource(R.string.tooltip_edit_tracker_copy_world_link),
                                            modifier = Modifier.weight(1f),
                                            centeredContent = {
                                                Icon(
                                                    imageVector = Icons.Filled.ContentCopy,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = stringResource(R.string.trackers_action_copy_world_share_link),
                                                    style = MaterialTheme.typography.button,
                                                )
                                            },
                                        )
                                        GeoVaultSecondaryButton(
                                            text = stringResource(R.string.trackers_action_share_world_share_link),
                                            onClick = {
                                                shareWorldShareLink(context, dialog.worldShareUrlDraft)
                                            },
                                            enabled = !isSaving && !dialog.worldShareUrlDraft.isNullOrBlank(),
                                            tooltip = stringResource(R.string.tooltip_edit_tracker_share_world_link),
                                            modifier = Modifier.weight(1f),
                                            centeredContent = {
                                                Text(
                                                    text = stringResource(R.string.trackers_action_share_world_share_link),
                                                    style = MaterialTheme.typography.button,
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(
                                                    imageVector = Icons.Filled.Share,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            TrackerShareVisibility.PRIVATE -> Unit
                        }
                    }
                    SharingSectionSavingOverlay(isSaving = dialog.isWorldShareLinkLoading)
                }
            }
        }
    }

    GeoVaultFormSection {
        if (dialog.tracker.isOwner()) {
            GeoVaultSecondaryButton(
                text = stringResource(R.string.trackers_action_export_kml),
                onClick = onExportKml,
                enabled = !isSaving && !isKmlExportLoading,
                tooltip = stringResource(R.string.tooltip_edit_tracker_export_kml),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        GeoVaultSecondaryButton(
            text = stringResource(R.string.trackers_action_clear_history),
            onClick = onClearHistory,
            enabled = !isSaving,
            tooltip = stringResource(R.string.tooltip_edit_tracker_clear_history),
            modifier = Modifier.fillMaxWidth(),
        )
        GeoVaultSecondaryButton(
            text = stringResource(R.string.trackers_action_delete_tracker),
            onClick = onDeleteTracker,
            enabled = !isSaving,
            accentColor = destructiveAccent,
            tooltip = stringResource(R.string.tooltip_edit_tracker_delete),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showPickUsersDialog) {
        var hasSeenLoading by remember { mutableStateOf(false) }
        LaunchedEffect(isShareRecipientSuggestionsLoading) {
            if (isShareRecipientSuggestionsLoading) hasSeenLoading = true
        }
        val effectivelyLoading = isShareRecipientSuggestionsLoading || !hasSeenLoading

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
            onDismiss = { showPickUsersDialog = false },
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
            onSearchQueryChange = { shareUserPickerSearch = it },
            searchLabel = stringResource(R.string.trackers_edit_share_user_picker_filter_label),
            searchPlaceholder = stringResource(R.string.trackers_edit_share_user_picker_filter_hint),
            emptyLabel = stringResource(R.string.trackers_no_other_users_found),
            confirmText = stringResource(R.string.trackers_edit_pick_users_done),
            isLoading = effectivelyLoading,
            loadingText = stringResource(R.string.trackers_share_suggestions_loading),
            enabled = !isSaving,
        )
    }
}

@Composable
private fun BoxScope.SharingSectionSavingOverlay(isSaving: Boolean) {
    if (!isSaving) return
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(MaterialTheme.colors.surface.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        GeoVaultLoadingSpinner(spinnerSize = 28.dp)
    }
}

@Composable
private fun EditVisibilityPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val m = modifier.fillMaxWidth()
    if (selected) {
        GeoVaultPrimaryButton(
            text = label,
            onClick = onClick,
            enabled = enabled,
            modifier = m,
        )
    } else {
        GeoVaultSecondaryButton(
            text = label,
            onClick = onClick,
            enabled = enabled,
            modifier = m,
        )
    }
}

private fun copyShareLink(
    context: Context,
    clipboardHelper: ClipboardCopyHelper,
    shareUrl: String?,
    label: String,
) {
    if (shareUrl.isNullOrBlank()) return
    clipboardHelper.copyText(resolveShareUrl(context, shareUrl), label)
}

private fun resolveShareUrl(context: Context, shareUrl: String): String {
    val trimmed = shareUrl.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val baseUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
    if (baseUrl.isBlank()) return trimmed
    return if (trimmed.startsWith("/")) "$baseUrl$trimmed" else "$baseUrl/$trimmed"
}

private fun shareWorldShareLink(context: Context, worldShareUrl: String?) {
    if (worldShareUrl.isNullOrBlank()) return
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, worldShareUrl)
    }
    context.startActivity(Intent.createChooser(shareIntent, null))
}

private data class TrackerColorPreviewState(
    val backgroundColor: Color,
    val showInvalidBadge: Boolean,
)

private fun resolveTrackerColorPreview(colorDraft: String): TrackerColorPreviewState {
    val normalized = colorDraft.trim()
    if (normalized.isEmpty()) {
        return TrackerColorPreviewState(
            backgroundColor = Color(parseHexToColorInt(null)),
            showInvalidBadge = false,
        )
    }
    return if (isValidTrackerHexInput(normalized)) {
        TrackerColorPreviewState(
            backgroundColor = Color(parseHexToColorInt(normalized)),
            showInvalidBadge = false,
        )
    } else {
        TrackerColorPreviewState(
            backgroundColor = Color.White,
            showInvalidBadge = true,
        )
    }
}

private fun isValidTrackerHexInput(input: String): Boolean {
    val hex = if (input.startsWith("#")) input.substring(1) else input
    if (hex.isEmpty()) return false
    val validLength = hex.length == 3 || hex.length == 4 || hex.length == 6 || hex.length == 8
    if (!validLength) return false
    return hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}
