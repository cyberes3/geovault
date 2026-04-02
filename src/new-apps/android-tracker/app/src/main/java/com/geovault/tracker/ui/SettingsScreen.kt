package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultInitialAuthView
import com.geovault.common.ui.components.GeoVaultInput
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
    onKeepScreenOnMap: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(title = "Settings")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    onKeepScreenOnMap = onKeepScreenOnMap,
                )
            }
            if (!state.infoMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(state.infoMessage)
            }
        }
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
    onKeepScreenOnMap: (Boolean) -> Unit,
) {
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
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            var distanceText by remember { mutableStateOf(formatFloatForField(s.distanceFilterMeters)) }
            LaunchedEffect(s.distanceFilterMeters) {
                distanceText = formatFloatForField(s.distanceFilterMeters)
            }
            GeoVaultInput(
                value = distanceText,
                onValueChange = {
                    distanceText = it
                    onDistanceFilterInput(it)
                },
                label = stringResource(R.string.settings_tracker_distance_label),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            var accuracyText by remember { mutableStateOf(formatFloatForField(s.accuracyFilterMeters)) }
            LaunchedEffect(s.accuracyFilterMeters) {
                accuracyText = formatFloatForField(s.accuracyFilterMeters)
            }
            GeoVaultInput(
                value = accuracyText,
                onValueChange = {
                    accuracyText = it
                    onAccuracyFilterInput(it)
                },
                label = stringResource(R.string.settings_tracker_accuracy_label),
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
            )
            Spacer(modifier = Modifier.height(4.dp))
            GeoVaultToggle(
                checked = s.keepScreenOnWhileViewingMap,
                onCheckedChange = onKeepScreenOnMap,
                label = stringResource(R.string.settings_tracker_keep_screen_on_map),
            )
        }
    }
}

private fun formatFloatForField(value: Float): String {
    return if (value == value.toLong().toFloat()) {
        value.toLong().toString()
    } else {
        value.toString()
    }
}

@Composable
private fun ProfilePicker(
    selected: TrackerTrackingProfile,
    onSelect: (TrackerTrackingProfile) -> Unit,
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
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                GeoVaultSecondaryButton(
                    text = label,
                    onClick = { onSelect(profile) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
