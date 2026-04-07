package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.tracker.presentation.SettingsMeasurementPolicy
import com.geovault.tracker.presentation.HiddenMapItem
import com.geovault.tracker.presentation.HiddenMapItemType
import com.geovault.tracker.presentation.SelectableTracker
import com.geovault.common.ui.components.GeoVaultInitialAuthView
import com.geovault.common.ui.components.GeoVaultInfoDialog
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultServerConfigBlock
import com.geovault.common.ui.components.GeoVaultToggle
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.presentation.SettingsState
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerTrackingProfile

@Composable
fun SettingsScreen(
    state: SettingsState,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTrackingProfileSelected: (TrackerTrackingProfile) -> Unit,
    onLoggingIntervalInput: (String) -> Unit,
    onDistanceFilterInput: (String) -> Unit,
    onAccuracyFilterInput: (String) -> Unit,
    onLowAccuracyFallbackEnabled: (Boolean) -> Unit,
    onLowAccuracyTimeoutInput: (String) -> Unit,
    onStartOnBoot: (Boolean) -> Unit,
    onStartOnLaunch: (Boolean) -> Unit,
    onSendExtendedData: (Boolean) -> Unit,
    onSignificantMotionOnly: (Boolean) -> Unit,
    onAutoTrackingMode: (Boolean) -> Unit,
    onKeepScreenOnMap: (Boolean) -> Unit,
    onRefreshSelectableTrackers: () -> Unit,
    onSetSelectedTracker: (String) -> Unit,
    onClearSelectedTracker: () -> Unit,
    onRefreshHiddenMapItems: () -> Unit,
    onUnhideMapItem: (HiddenMapItem) -> Unit,
    onUnhideAllMapItems: () -> Unit,
    onOpenAllTrackersOnMap: () -> Unit = {},
) {
    var showSelectedTrackerDialog by remember { mutableStateOf(false) }
    var showHiddenMapItemsDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(title = stringResource(R.string.settings_screen_title))
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
            if (!state.isLoggedIn) {
                GeoVaultInitialAuthView(
                    serverUrl = state.serverUrl,
                    onServerUrlChanged = onServerUrlChanged,
                    onConnect = onConnect,
                    isConnecting = state.isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val email = state.loggedInText
                    .removePrefix("Logged in as").trim()
                    .ifBlank { "Authenticated User" }
                GeoVaultServerConfigBlock(
                    serverUrl = state.serverUrl,
                    loggedInEmail = email,
                    onDisconnectConfirmed = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(24.dp))
                TrackerSettingsSection(
                    state = state,
                    onTrackingProfileSelected = onTrackingProfileSelected,
                    onLoggingIntervalInput = onLoggingIntervalInput,
                    onDistanceFilterInput = onDistanceFilterInput,
                    onAccuracyFilterInput = onAccuracyFilterInput,
                    onLowAccuracyFallbackEnabled = onLowAccuracyFallbackEnabled,
                    onLowAccuracyTimeoutInput = onLowAccuracyTimeoutInput,
                    onStartOnBoot = onStartOnBoot,
                    onStartOnLaunch = onStartOnLaunch,
                    onSendExtendedData = onSendExtendedData,
                    onSignificantMotionOnly = onSignificantMotionOnly,
                    onAutoTrackingMode = onAutoTrackingMode,
                    onKeepScreenOnMap = onKeepScreenOnMap,
                    selectedTrackerId = state.selectedTrackerId,
                    selectedTrackerName = state.selectedTrackerName,
                    onManageSelectedTracker = {
                        onRefreshSelectableTrackers()
                        showSelectedTrackerDialog = true
                    },
                    onManageHiddenItems = {
                        onRefreshHiddenMapItems()
                        showHiddenMapItemsDialog = true
                    },
                    onOpenAllTrackersOnMap = onOpenAllTrackersOnMap,
                )
            }
            if (!state.infoMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(state.infoMessage)
            }
            }
            TrackerParamsOverlayLayer()
        }
    }
    if (showSelectedTrackerDialog) {
        SelectedTrackerDialog(
            selectedTrackerId = state.selectedTrackerId,
            selectedTrackerName = state.selectedTrackerName,
            selectableTrackers = state.selectableTrackers,
            isLoading = state.isSelectableTrackersLoading,
            onDismiss = { showSelectedTrackerDialog = false },
            onSelectTracker = { trackerId ->
                onSetSelectedTracker(trackerId)
                showSelectedTrackerDialog = false
            },
            onClearSelection = {
                onClearSelectedTracker()
                showSelectedTrackerDialog = false
            },
        )
    }
    if (showHiddenMapItemsDialog) {
        HiddenMapItemsDialog(
            items = state.hiddenMapItems,
            isUpdating = state.isHiddenMapItemsUpdating,
            onDismiss = { showHiddenMapItemsDialog = false },
            onUnhideOne = onUnhideMapItem,
            onUnhideAll = onUnhideAllMapItems,
        )
    }
}

@Composable
private fun TrackerSettingsSection(
    state: SettingsState,
    onTrackingProfileSelected: (TrackerTrackingProfile) -> Unit,
    onLoggingIntervalInput: (String) -> Unit,
    onDistanceFilterInput: (String) -> Unit,
    onAccuracyFilterInput: (String) -> Unit,
    onLowAccuracyFallbackEnabled: (Boolean) -> Unit,
    onLowAccuracyTimeoutInput: (String) -> Unit,
    onStartOnBoot: (Boolean) -> Unit,
    onStartOnLaunch: (Boolean) -> Unit,
    onSendExtendedData: (Boolean) -> Unit,
    onSignificantMotionOnly: (Boolean) -> Unit,
    onAutoTrackingMode: (Boolean) -> Unit,
    onKeepScreenOnMap: (Boolean) -> Unit,
    selectedTrackerId: String,
    selectedTrackerName: String,
    onManageSelectedTracker: () -> Unit,
    onManageHiddenItems: () -> Unit,
    onOpenAllTrackersOnMap: () -> Unit,
) {
    var showLoggingHelpDialog by remember { mutableStateOf(false) }
    Divider(
        color = GeoVaultColorTokens.BorderLight,
        thickness = 1.dp,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.settings_tracker_section_title),
        style = MaterialTheme.typography.subtitle1,
    )
    Spacer(modifier = Modifier.height(12.dp))
    when (state.trackerLoadState) {
        TrackerSettingsLoadState.Loading -> {
            Text(
                text = stringResource(R.string.settings_tracker_loading),
                style = MaterialTheme.typography.body2,
            )
        }
        TrackerSettingsLoadState.Error -> {
            Text(
                text = stringResource(R.string.settings_tracker_error),
                style = MaterialTheme.typography.body2,
            )
        }
        TrackerSettingsLoadState.Ready -> {
            val s = state.trackerSettings
            Text(
                text = stringResource(R.string.settings_tracker_profile_title),
                style = MaterialTheme.typography.body2,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ProfilePicker(
                selected = s.trackingProfile,
                onSelect = onTrackingProfileSelected,
                enabled = !s.autoTrackingMode,
            )
            Spacer(modifier = Modifier.height(16.dp))
            var intervalText by remember { mutableStateOf(s.loggingIntervalSec.toString()) }
            LaunchedEffect(s.loggingIntervalSec) {
                intervalText = s.loggingIntervalSec.toString()
            }
            GeoVaultInput(
                value = intervalText,
                onValueChange = {
                    intervalText = it
                    onLoggingIntervalInput(it)
                },
                label = stringResource(R.string.settings_tracker_interval_label),
                enabled = !s.autoTrackingMode,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            GeoVaultSecondaryButton(
                text = stringResource(R.string.settings_tracker_logging_help_button),
                onClick = { showLoggingHelpDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            val unitLabel = stringResource(
                if (state.usesImperialUnits) R.string.unit_ft else R.string.unit_m
            )
            var distanceText by remember {
                mutableStateOf(
                    SettingsMeasurementPolicy.metersToDisplayText(
                        meters = s.distanceFilterMeters,
                        usesImperial = state.usesImperialUnits
                    )
                )
            }
            LaunchedEffect(s.distanceFilterMeters, state.usesImperialUnits) {
                distanceText = SettingsMeasurementPolicy.metersToDisplayText(
                    meters = s.distanceFilterMeters,
                    usesImperial = state.usesImperialUnits
                )
            }
            GeoVaultInput(
                value = distanceText,
                onValueChange = {
                    distanceText = it
                    onDistanceFilterInput(it)
                },
                label = stringResource(R.string.settings_tracker_distance_label, unitLabel),
                enabled = !s.autoTrackingMode,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            var accuracyText by remember {
                mutableStateOf(
                    SettingsMeasurementPolicy.metersToDisplayText(
                        meters = s.accuracyFilterMeters,
                        usesImperial = state.usesImperialUnits
                    )
                )
            }
            LaunchedEffect(s.accuracyFilterMeters, state.usesImperialUnits) {
                accuracyText = SettingsMeasurementPolicy.metersToDisplayText(
                    meters = s.accuracyFilterMeters,
                    usesImperial = state.usesImperialUnits
                )
            }
            GeoVaultInput(
                value = accuracyText,
                onValueChange = {
                    accuracyText = it
                    onAccuracyFilterInput(it)
                },
                label = stringResource(R.string.settings_tracker_accuracy_label, unitLabel),
                enabled = !s.autoTrackingMode,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            GeoVaultToggle(
                checked = s.lowAccuracyFallbackEnabled,
                onCheckedChange = onLowAccuracyFallbackEnabled,
                label = stringResource(R.string.settings_tracker_low_accuracy_fallback),
            )
            Spacer(modifier = Modifier.height(8.dp))
            var fallbackTimeoutText by remember { mutableStateOf(s.lowAccuracyFallbackTimeoutSec.toString()) }
            LaunchedEffect(s.lowAccuracyFallbackTimeoutSec) {
                fallbackTimeoutText = s.lowAccuracyFallbackTimeoutSec.toString()
            }
            GeoVaultInput(
                value = fallbackTimeoutText,
                onValueChange = {
                    fallbackTimeoutText = it
                    onLowAccuracyTimeoutInput(it)
                },
                label = stringResource(R.string.settings_tracker_low_accuracy_timeout_label),
                enabled = s.lowAccuracyFallbackEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            GeoVaultToggle(
                checked = s.startOnBoot,
                onCheckedChange = onStartOnBoot,
                label = stringResource(R.string.settings_tracker_start_on_boot),
            )
            Spacer(modifier = Modifier.height(4.dp))
            GeoVaultToggle(
                checked = s.startTrackingOnLaunch,
                onCheckedChange = onStartOnLaunch,
                label = stringResource(R.string.settings_tracker_start_on_launch),
            )
            Spacer(modifier = Modifier.height(4.dp))
            GeoVaultToggle(
                checked = s.sendExtendedData,
                onCheckedChange = onSendExtendedData,
                label = stringResource(R.string.settings_tracker_send_extended),
            )
            Spacer(modifier = Modifier.height(4.dp))
            GeoVaultToggle(
                checked = s.significantDataOnly,
                onCheckedChange = onSignificantMotionOnly,
                label = stringResource(R.string.settings_tracker_significant_motion_only),
                enabled = state.significantMotionSensorAvailable,
            )
            if (!state.significantMotionSensorAvailable) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_motion_sensor_unavailable),
                    style = MaterialTheme.typography.caption,
                    color = GeoVaultColorTokens.TextSecondary,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            GeoVaultToggle(
                checked = s.autoTrackingMode,
                onCheckedChange = onAutoTrackingMode,
                label = stringResource(R.string.settings_tracker_auto_tracking_mode),
            )
            Spacer(modifier = Modifier.height(4.dp))
            GeoVaultToggle(
                checked = s.keepScreenOnWhileViewingMap,
                onCheckedChange = onKeepScreenOnMap,
                label = stringResource(R.string.settings_tracker_keep_screen_on_map),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(
                    R.string.settings_tracker_selected_tracker_label,
                    selectedTrackerName.ifBlank { stringResource(R.string.settings_tracker_none_selected) }
                ),
                style = MaterialTheme.typography.body2,
            )
            Spacer(modifier = Modifier.height(8.dp))
            GeoVaultSecondaryButton(
                text = if (selectedTrackerId.isBlank()) {
                    stringResource(R.string.settings_tracker_manage_selected_tracker)
                } else {
                    stringResource(R.string.settings_tracker_change_selected_tracker)
                },
                onClick = onManageSelectedTracker,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            GeoVaultSecondaryButton(
                text = stringResource(R.string.settings_tracker_manage_hidden_items),
                onClick = onManageHiddenItems,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            GeoVaultSecondaryButton(
                text = stringResource(R.string.settings_tracker_view_all_tracks_on_map),
                onClick = onOpenAllTrackersOnMap,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (showLoggingHelpDialog) {
        GeoVaultInfoDialog(
            title = stringResource(R.string.settings_logging_help_title),
            onDismissRequest = { showLoggingHelpDialog = false },
            closeButtonText = stringResource(R.string.trackers_dialog_cancel),
        ) {
            Text(stringResource(R.string.settings_logging_help_message))
        }
    }
}

@Composable
private fun ProfilePicker(
    selected: TrackerTrackingProfile,
    onSelect: (TrackerTrackingProfile) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TrackerTrackingProfile.entries.forEach { profile ->
            val label = when (profile) {
                TrackerTrackingProfile.WALKING -> stringResource(R.string.settings_tracker_profile_walking)
                TrackerTrackingProfile.BIKING -> stringResource(R.string.settings_tracker_profile_biking)
                TrackerTrackingProfile.DRIVING -> stringResource(R.string.settings_tracker_profile_driving)
                TrackerTrackingProfile.CUSTOM -> stringResource(R.string.settings_tracker_profile_custom)
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (profile == selected) {
                GeoVaultPrimaryButton(
                    text = label,
                    onClick = { onSelect(profile) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                GeoVaultSecondaryButton(
                    text = label,
                    onClick = { onSelect(profile) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HiddenMapItemsDialog(
    items: List<HiddenMapItem>,
    isUpdating: Boolean,
    onDismiss: () -> Unit,
    onUnhideOne: (HiddenMapItem) -> Unit,
    onUnhideAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_hidden_items_title)) },
        text = {
            if (isUpdating) {
                GeoVaultLoadingSpinner(spinnerSize = 24.dp)
            } else if (items.isEmpty()) {
                Text(stringResource(R.string.settings_hidden_items_empty))
            } else {
                Column {
                    items.forEach { item ->
                        val typeLabel = if (item.type == HiddenMapItemType.TRACKER) {
                            stringResource(R.string.settings_hidden_item_tracker)
                        } else {
                            stringResource(R.string.settings_hidden_item_group)
                        }
                        Text(
                            text = "$typeLabel: ${item.name}",
                            style = MaterialTheme.typography.body2,
                        )
                        TextButton(
                            onClick = { onUnhideOne(item) },
                            enabled = !isUpdating,
                        ) {
                            Text(stringResource(R.string.settings_hidden_item_unhide))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onUnhideAll,
                enabled = !isUpdating && items.isNotEmpty(),
            ) {
                Text(stringResource(R.string.settings_hidden_items_unhide_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isUpdating) {
                Text(stringResource(R.string.trackers_dialog_cancel))
            }
        }
    )
}

@Composable
private fun SelectedTrackerDialog(
    selectedTrackerId: String,
    selectedTrackerName: String,
    selectableTrackers: List<SelectableTracker>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelectTracker: (String) -> Unit,
    onClearSelection: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tracker_select_title)) },
        text = {
            if (isLoading) {
                GeoVaultLoadingSpinner(spinnerSize = 24.dp)
            } else if (selectableTrackers.isEmpty()) {
                Text(stringResource(R.string.settings_tracker_select_empty))
            } else {
                LazyColumn {
                    item {
                        Text(
                            text = stringResource(
                                R.string.settings_tracker_selected_tracker_label,
                                selectedTrackerName.ifBlank { stringResource(R.string.settings_tracker_none_selected) }
                            ),
                            style = MaterialTheme.typography.body2,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(items = selectableTrackers, key = { it.id }) { tracker ->
                        GeoVaultSecondaryButton(
                            text = if (tracker.id == selectedTrackerId) {
                                stringResource(R.string.settings_tracker_selected_prefix, tracker.name)
                            } else {
                                tracker.name
                            },
                            onClick = { onSelectTracker(tracker.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onClearSelection,
                enabled = selectedTrackerId.isNotBlank() && !isLoading
            ) {
                Text(stringResource(R.string.settings_tracker_clear_selected))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(R.string.trackers_dialog_cancel))
            }
        },
    )
}
