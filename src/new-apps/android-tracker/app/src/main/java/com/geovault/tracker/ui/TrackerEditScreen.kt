package com.geovault.tracker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultDropdownSelect
import com.geovault.common.ui.components.GeoVaultFormSection
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultSelectOption
import com.geovault.common.ui.components.GeoVaultToggleHelpCard
import com.geovault.common.ui.components.GeoVaultCompactDismissTitleBar
import com.geovault.common.NaturalSort
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.UserItem
import com.geovault.tracker.TrackerRecentDataWindowOptions
import com.geovault.tracker.parseHexToColorInt
import com.geovault.tracker.showHueColorPickerDialog
import com.geovault.tracker.presentation.TrackersGroupsDialog
import com.geovault.tracker.presentation.TrackerShareVisibility
import com.geovault.tracker.presentation.TrackerSharingSettingsPolicy
import java.util.Locale

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
fun TrackerEditScreen(
    dialog: TrackersGroupsDialog.EditTracker,
    shareRecipientUsers: List<UserItem>,
    isShareRecipientSuggestionsLoading: Boolean,
    isKmlExportLoading: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onReloadShareRecipients: () -> Unit,
    onNameDraftChanged: (String) -> Unit,
    onColorDraftChanged: (String) -> Unit,
    onSetAsSelectedChanged: (Boolean) -> Unit,
    onHiddenChanged: (Boolean) -> Unit,
    onRecentDataWindowChanged: (String) -> Unit,
    onVisibilityChanged: (TrackerShareVisibility) -> Unit,
    onShareParamsWithRecipientsChanged: (Boolean) -> Unit,
    onAllowGroupReshareChanged: (Boolean) -> Unit,
    onToggleSharedEmail: (String) -> Unit,
    onWorldShareEnabledChanged: (Boolean) -> Unit,
    onShareParamsWithWorldChanged: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteTracker: () -> Unit,
    onExportKml: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val colorPreview = remember(dialog.colorDraft, context) {
        resolveTrackerColorPreview(dialog.colorDraft, context)
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

    LaunchedEffect(showPickUsersDialog) {
        if (showPickUsersDialog) {
            shareUserPickerSearch = ""
            onReloadShareRecipients()
        }
    }

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
    val hasUnsavedChanges = remember(
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

    BackHandler(enabled = true) {
        dismissWithGuard()
    }

    val sharedRecipientCount = TrackerSharingSettingsPolicy.parseSharedEmails(dialog.sharedEmailsDraft).size
    val destructiveAccent =
        if (isSystemInDarkTheme()) GeoVaultColorTokens.DarkError else GeoVaultColorTokens.Error
    val sharingSectionBackground =
        if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.Gray300.copy(alpha = 0.58f)
        } else {
            MaterialTheme.colors.onSurface.copy(alpha = 0.10f)
        }

    Scaffold(
        topBar = {
            GeoVaultCompactDismissTitleBar(
                title = stringResource(R.string.trackers_dialog_edit_tracker_details_title),
                onClose = dismissWithGuard,
                closeContentDescription = stringResource(R.string.trackers_dialog_cancel),
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                GeoVaultPrimaryButton(
                    text = stringResource(R.string.trackers_dialog_save),
                    onClick = onSave,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
                Text(
                    text = stringResource(R.string.trackers_field_color_optional),
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = GeoVaultColorTokens.TextPrimary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colorPreview.backgroundColor),
                        contentAlignment = Alignment.Center,
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
                    GeoVaultSecondaryButton(
                        text = stringResource(R.string.trackers_action_pick_tracker_color),
                        onClick = {
                            showHueColorPickerDialog(
                                context = context,
                                initialHex = dialog.colorDraft.ifBlank { null },
                                onColorPicked = onColorDraftChanged,
                            )
                        },
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                    )
                    GeoVaultInput(
                        value = dialog.colorDraft,
                        onValueChange = onColorDraftChanged,
                        label = null,
                        singleLine = true,
                        placeholder = stringResource(R.string.trackers_field_color_hint),
                        modifier = Modifier.widthIn(min = 96.dp, max = 132.dp),
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
                GeoVaultDropdownSelect(
                    label = stringResource(R.string.trackers_edit_recent_data_filter_title),
                    selectedValue = dialog.recentDataWindowDraft,
                    options = recentOptions,
                    onValueSelected = onRecentDataWindowChanged,
                    enabled = !isSaving,
                )
                Text(
                    text = stringResource(R.string.trackers_edit_recent_data_help),
                    style = MaterialTheme.typography.caption,
                    color = GeoVaultColorTokens.TextSecondary,
                )
            }

            if (dialog.tracker.isOwner()) {
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
                                    color = GeoVaultColorTokens.TextSecondary,
                                )
                            }
                            when (dialog.visibilityDraft) {
                                TrackerShareVisibility.SHARED,
                                TrackerShareVisibility.PUBLIC -> {
                                    if (dialog.visibilityDraft == TrackerShareVisibility.SHARED) {
                                        GeoVaultSecondaryButton(
                                            text = stringResource(R.string.trackers_edit_pick_users),
                                            onClick = { showPickUsersDialog = true },
                                            enabled = !isSaving,
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
                                    if (dialog.worldShareEnabledDraft) {
                                        GeoVaultToggleHelpCard(
                                            checked = dialog.shareParamsWithWorldDraft,
                                            onCheckedChange = onShareParamsWithWorldChanged,
                                            title = stringResource(R.string.trackers_field_share_params_with_world),
                                            helpText = stringResource(R.string.trackers_edit_share_params_world_help),
                                            enabled = !isSaving && !dialog.isWorldShareLinkLoading,
                                        )
                                        if (dialog.isWorldShareLinkLoading) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                elevation = 0.dp,
                                                border = BorderStroke(1.dp, GeoVaultColorTokens.PrimaryBlue),
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
                                                    copyWorldShareLink(context, dialog.worldShareUrlDraft)
                                                },
                                                enabled = !isSaving && !dialog.worldShareUrlDraft.isNullOrBlank(),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                                TrackerShareVisibility.PRIVATE -> Unit
                            }
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                GeoVaultSecondaryButton(
                    text = stringResource(R.string.trackers_action_clear_history),
                    onClick = onClearHistory,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                GeoVaultSecondaryButton(
                    text = stringResource(R.string.trackers_action_delete_tracker),
                    onClick = onDeleteTracker,
                    enabled = !isSaving,
                    accentColor = destructiveAccent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showPickUsersDialog) {
        val selectedEmails = TrackerSharingSettingsPolicy.parseSharedEmails(dialog.sharedEmailsDraft)
        val apiEmailsLower = shareRecipientUsers
            .map { it.email.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotEmpty() }
        val apiEmailSet = apiEmailsLower.toSet()
        val pinnedEmails = selectedEmails.filter { it !in apiEmailSet }
        val pickerRowEmails = (apiEmailsLower + pinnedEmails).distinct()
            .sortedWith(NaturalSort.naturalOrderBy { it })
        val q = shareUserPickerSearch.trim()
        val filteredPickerEmails = if (q.isEmpty()) {
            pickerRowEmails
        } else {
            pickerRowEmails.filter { it.contains(q, ignoreCase = true) }
        }
        Dialog(onDismissRequest = { showPickUsersDialog = false }) {
            val pickerWidth = LocalConfiguration.current.screenWidthDp.dp * 0.8f
            Card(
                modifier = Modifier.width(pickerWidth),
                elevation = 0.dp,
                backgroundColor = MaterialTheme.colors.surface,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.trackers_edit_pick_users),
                        style = MaterialTheme.typography.subtitle1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.trackers_edit_share_user_picker_hint),
                        style = MaterialTheme.typography.caption,
                        color = GeoVaultColorTokens.TextSecondary,
                    )
                    GeoVaultInput(
                        value = shareUserPickerSearch,
                        onValueChange = { shareUserPickerSearch = it },
                        label = stringResource(R.string.trackers_edit_share_user_picker_filter_label),
                        placeholder = stringResource(R.string.trackers_edit_share_user_picker_filter_hint),
                        singleLine = true,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        isShareRecipientSuggestionsLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    GeoVaultLoadingSpinner()
                                    Text(
                                        text = stringResource(R.string.trackers_share_suggestions_loading),
                                        style = MaterialTheme.typography.body2,
                                    )
                                }
                            }
                        }
                        filteredPickerEmails.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.trackers_no_other_users_found),
                                style = MaterialTheme.typography.body2,
                                color = GeoVaultColorTokens.TextSecondary,
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 360.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(filteredPickerEmails, key = { it }) { emailLower ->
                                    val displayEmail = shareRecipientUsers
                                        .firstOrNull {
                                            it.email.trim().lowercase(Locale.getDefault()) == emailLower
                                        }
                                        ?.email
                                        ?.trim()
                                        ?: emailLower
                                    ShareUserPickerRow(
                                        displayEmail = displayEmail,
                                        selected = selectedEmails.contains(emailLower),
                                        enabled = !isSaving,
                                        onClick = { onToggleSharedEmail(emailLower) },
                                    )
                                }
                            }
                        }
                    }
                    GeoVaultPrimaryButton(
                        text = stringResource(R.string.trackers_edit_pick_users_done),
                        onClick = { showPickUsersDialog = false },
                        modifier = Modifier.fillMaxWidth(),
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
                .background(Color(0x66000000))
        ) {
            GeoVaultLoadingSpinner(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            )
        }
    }
}

/** Matches legacy [item_shared_user_picker_row]: bordered card, blue fill + check when selected. */
@Composable
private fun ShareUserPickerRow(
    displayEmail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isDark = !MaterialTheme.colors.isLight
    val rowBackground = when {
        !selected -> MaterialTheme.colors.surface
        isDark -> GeoVaultColorTokens.PrimaryBlue.copy(alpha = 0.22f)
        else -> Color(0xFFE4EAF5)
    }
    val labelColor = if (selected) {
        GeoVaultColorTokens.PrimaryBlue
    } else {
        MaterialTheme.colors.onSurface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = rowBackground,
        border = BorderStroke(1.dp, GeoVaultColorTokens.PrimaryBlue),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayEmail,
                modifier = Modifier.weight(1f),
                color = labelColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = GeoVaultColorTokens.PrimaryBlue,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(20.dp))
            }
        }
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

private fun copyWorldShareLink(context: Context, worldShareUrl: String?) {
    if (worldShareUrl.isNullOrBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("World share link", worldShareUrl))
}

private data class TrackerColorPreviewState(
    val backgroundColor: Color,
    val showInvalidBadge: Boolean,
)

private fun resolveTrackerColorPreview(colorDraft: String, context: Context): TrackerColorPreviewState {
    val normalized = colorDraft.trim()
    if (normalized.isEmpty()) {
        return TrackerColorPreviewState(
            backgroundColor = Color(parseHexToColorInt(null, context)),
            showInvalidBadge = false,
        )
    }
    return if (isValidTrackerHexInput(normalized)) {
        TrackerColorPreviewState(
            backgroundColor = Color(parseHexToColorInt(normalized, context)),
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
