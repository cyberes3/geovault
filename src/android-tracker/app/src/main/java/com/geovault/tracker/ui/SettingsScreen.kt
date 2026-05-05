package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import com.geovault.common.ui.components.GeoVaultIconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.geovault.common.ui.components.rememberConnectingButtonState
import com.geovault.tracker.presentation.SettingsMeasurementPolicy
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.common.ui.components.GeoVaultInfoDialog
import com.geovault.common.ui.components.GeoVaultInitialAuthView
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultSelectField
import com.geovault.common.ui.components.GeoVaultSelectOption
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultPullRefreshLoadingContainer
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultServerConfigBlock
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.components.GeoVaultToggleHelpCard
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.modifier.geoVaultKeyboardAwareVerticalScroll
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor
import com.geovault.tracker.R
import com.geovault.tracker.presentation.HiddenTrackerItem
import com.geovault.tracker.presentation.HiddenTrackerItemType
import com.geovault.tracker.presentation.SettingsAutoModePolicy
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
    onRefreshHiddenTrackerItems: () -> Unit,
    onUnhideTrackerItem: (HiddenTrackerItem) -> Unit,
    onUnhideAllTrackerItems: () -> Unit,
    onOpenAllTrackersOnMap: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLoggingHelpDialog by remember { mutableStateOf(false) }
    var showHiddenTrackersOverlay by remember { mutableStateOf(false) }
    var isBindingSettings by remember { mutableStateOf(true) }
    var hasHydratedSettings by remember { mutableStateOf(false) }
    var lastRenderRevision by remember { mutableStateOf(-1L) }
    var lastRenderImperial by remember { mutableStateOf(state.usesImperialUnits) }
    var isUpdatingFromProfile by remember { mutableStateOf(false) }

    var selectedProfile by remember { mutableStateOf(TrackerTrackingProfile.BIKING) }
    var intervalText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf("") }
    var accuracyText by remember { mutableStateOf("") }
    var lowAccuracyTimeoutText by remember { mutableStateOf("") }

    var intervalFocused by remember { mutableStateOf(false) }
    var distanceFocused by remember { mutableStateOf(false) }
    var accuracyFocused by remember { mutableStateOf(false) }
    var timeoutFocused by remember { mutableStateOf(false) }
    val autoText = stringResource(R.string.profile_auto)

    fun shouldIgnoreSettingChange(): Boolean = isBindingSettings || !hasHydratedSettings

    fun promoteToCustomProfile() {
        if (selectedProfile != TrackerTrackingProfile.CUSTOM) {
            selectedProfile = TrackerTrackingProfile.CUSTOM
            onTrackingProfileSelected(TrackerTrackingProfile.CUSTOM)
        }
    }

    fun applyTrackerSettingsToUi(force: Boolean) {
        if (state.trackerLoadState != TrackerSettingsLoadState.Ready) return
        val settings = state.trackerSettings
        isBindingSettings = true
        if (settings.autoTrackingMode) {
            if (SettingsAutoModePolicy.shouldOverwriteUiField(intervalFocused, force)) intervalText = autoText
            if (SettingsAutoModePolicy.shouldOverwriteUiField(distanceFocused, force)) distanceText = autoText
            if (SettingsAutoModePolicy.shouldOverwriteUiField(accuracyFocused, force)) accuracyText = autoText
            selectedProfile = settings.trackingProfile
        } else {
            selectedProfile = settings.trackingProfile
            val interval = settings.loggingIntervalSec.toString()
            val distance = SettingsMeasurementPolicy.metersToDisplayText(
                meters = settings.distanceFilterMeters,
                usesImperial = state.usesImperialUnits
            )
            val accuracy = SettingsMeasurementPolicy.metersToDisplayText(
                meters = settings.accuracyFilterMeters,
                usesImperial = state.usesImperialUnits
            )
            if (SettingsAutoModePolicy.shouldOverwriteUiField(intervalFocused, force)) intervalText = interval
            if (SettingsAutoModePolicy.shouldOverwriteUiField(distanceFocused, force)) distanceText = distance
            if (SettingsAutoModePolicy.shouldOverwriteUiField(accuracyFocused, force)) accuracyText = accuracy
        }
        val fallback = settings.lowAccuracyFallbackTimeoutSec.toString()
        if (SettingsAutoModePolicy.shouldOverwriteUiField(timeoutFocused, force)) {
            lowAccuracyTimeoutText = fallback
        }
        hasHydratedSettings = true
        isBindingSettings = false
    }

    fun maybePromoteCustomFromInterval(raw: String) {
        if (isBindingSettings || isUpdatingFromProfile || state.trackerLoadState != TrackerSettingsLoadState.Ready) {
            return
        }
        val parsed = raw.trim().toLongOrNull() ?: run {
            promoteToCustomProfile()
            return
        }
        val clamped = com.geovault.tracker.settings.TrackerSettings.clampLoggingIntervalSec(parsed)
        if (clamped != state.trackerSettings.loggingIntervalSec) promoteToCustomProfile()
    }

    fun maybePromoteCustomFromDistance(raw: String) {
        if (isBindingSettings || isUpdatingFromProfile || state.trackerLoadState != TrackerSettingsLoadState.Ready) {
            return
        }
        val parsedDisplay = raw.trim().toFloatOrNull() ?: run {
            promoteToCustomProfile()
            return
        }
        val meters = if (state.usesImperialUnits) parsedDisplay / 3.28084f else parsedDisplay
        val clamped = com.geovault.tracker.settings.TrackerSettings.clampDistanceFilterMeters(meters)
        if (kotlin.math.abs(clamped - state.trackerSettings.distanceFilterMeters) > 0.0001f) {
            promoteToCustomProfile()
        }
    }

    fun maybePromoteCustomFromAccuracy(raw: String) {
        if (isBindingSettings || isUpdatingFromProfile || state.trackerLoadState != TrackerSettingsLoadState.Ready) {
            return
        }
        val parsedDisplay = raw.trim().toFloatOrNull() ?: run {
            promoteToCustomProfile()
            return
        }
        val meters = if (state.usesImperialUnits) parsedDisplay / 3.28084f else parsedDisplay
        val clamped = com.geovault.tracker.settings.TrackerSettings.clampAccuracyFilterMeters(meters)
        if (kotlin.math.abs(clamped - state.trackerSettings.accuracyFilterMeters) > 0.0001f) {
            promoteToCustomProfile()
        }
    }

    fun commitInterval() {
        if (state.trackerLoadState != TrackerSettingsLoadState.Ready) return
        val parsed = intervalText.trim().toLongOrNull()
        if (parsed == null) {
            intervalText = state.trackerSettings.loggingIntervalSec.toString()
            return
        }
        onLoggingIntervalInput(parsed.toString())
        val clamped = com.geovault.tracker.settings.TrackerSettings.clampLoggingIntervalSec(parsed)
        if (clamped != state.trackerSettings.loggingIntervalSec) {
            promoteToCustomProfile()
        }
    }

    fun commitDistance() {
        if (state.trackerLoadState != TrackerSettingsLoadState.Ready) return
        val parsed = distanceText.trim().toFloatOrNull()
        if (parsed == null) {
            distanceText = SettingsMeasurementPolicy.metersToDisplayText(
                meters = state.trackerSettings.distanceFilterMeters,
                usesImperial = state.usesImperialUnits
            )
            return
        }
        onDistanceFilterInput(parsed.toString())
        val meters = if (state.usesImperialUnits) parsed / 3.28084f else parsed
        val clamped = com.geovault.tracker.settings.TrackerSettings.clampDistanceFilterMeters(meters)
        if (kotlin.math.abs(clamped - state.trackerSettings.distanceFilterMeters) > 0.0001f) {
            promoteToCustomProfile()
        }
    }

    fun commitAccuracy() {
        if (state.trackerLoadState != TrackerSettingsLoadState.Ready) return
        val parsed = accuracyText.trim().toFloatOrNull()
        if (parsed == null) {
            accuracyText = SettingsMeasurementPolicy.metersToDisplayText(
                meters = state.trackerSettings.accuracyFilterMeters,
                usesImperial = state.usesImperialUnits
            )
            return
        }
        onAccuracyFilterInput(parsed.toString())
        val meters = if (state.usesImperialUnits) parsed / 3.28084f else parsed
        val clamped = com.geovault.tracker.settings.TrackerSettings.clampAccuracyFilterMeters(meters)
        if (kotlin.math.abs(clamped - state.trackerSettings.accuracyFilterMeters) > 0.0001f) {
            promoteToCustomProfile()
        }
    }

    fun commitLowAccuracyTimeout() {
        if (state.trackerLoadState != TrackerSettingsLoadState.Ready) return
        val parsed = lowAccuracyTimeoutText.trim().toLongOrNull()
        if (parsed == null) {
            lowAccuracyTimeoutText = state.trackerSettings.lowAccuracyFallbackTimeoutSec.toString()
            return
        }
        onLowAccuracyTimeoutInput(parsed.toString())
    }

    LaunchedEffect(state.trackerLoadState, state.trackerRevision, state.usesImperialUnits) {
        if (state.trackerLoadState != TrackerSettingsLoadState.Ready) return@LaunchedEffect
        val shouldApply = state.trackerRevision != lastRenderRevision ||
            state.usesImperialUnits != lastRenderImperial
        if (shouldApply) {
            applyTrackerSettingsToUi(force = false)
            lastRenderRevision = state.trackerRevision
            lastRenderImperial = state.usesImperialUnits
        }
    }

    DisposableEffect(state.trackerLoadState, state.usesImperialUnits) {
        onDispose {
            if (isBindingSettings || state.trackerLoadState != TrackerSettingsLoadState.Ready) return@onDispose
            val autoTrackingEnabled = state.trackerSettings.autoTrackingMode
            if (SettingsAutoModePolicy.shouldApplyManualDefaults(autoTrackingEnabled)) {
                if (intervalText.trim().toLongOrNull() == null) {
                    onLoggingIntervalInput(com.geovault.tracker.settings.TrackerSettings.DEFAULT_LOGGING_INTERVAL_SEC.toString())
                } else {
                    commitInterval()
                }
                if (distanceText.trim().toFloatOrNull() == null) {
                    onDistanceFilterInput(
                        SettingsMeasurementPolicy.metersToDisplayText(
                            meters = com.geovault.tracker.settings.TrackerSettings.DEFAULT_DISTANCE_FILTER_METERS,
                            usesImperial = state.usesImperialUnits
                        )
                    )
                } else {
                    commitDistance()
                }
                if (accuracyText.trim().toFloatOrNull() == null) {
                    onAccuracyFilterInput(
                        SettingsMeasurementPolicy.metersToDisplayText(
                            meters = com.geovault.tracker.settings.TrackerSettings.DEFAULT_ACCURACY_FILTER_METERS,
                            usesImperial = state.usesImperialUnits
                        )
                    )
                } else {
                    commitAccuracy()
                }
            }
            if (lowAccuracyTimeoutText.trim().toLongOrNull() == null) {
                onLowAccuracyTimeoutInput(
                    com.geovault.tracker.settings.TrackerSettings.DEFAULT_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC.toString()
                )
            } else {
                commitLowAccuracyTimeout()
            }
        }
    }

    val profileDisplay = if (state.trackerSettings.autoTrackingMode) {
        stringResource(R.string.profile_auto)
    } else {
        when (selectedProfile) {
            TrackerTrackingProfile.WALKING -> stringResource(R.string.profile_walking)
            TrackerTrackingProfile.BIKING -> stringResource(R.string.profile_biking)
            TrackerTrackingProfile.DRIVING -> stringResource(R.string.profile_driving)
            TrackerTrackingProfile.CUSTOM -> stringResource(R.string.profile_custom)
        }
    }
    val profileOptions = if (state.trackerSettings.autoTrackingMode) {
        listOf(GeoVaultSelectOption(selectedProfile, profileDisplay))
    } else {
        listOf(
            GeoVaultSelectOption(TrackerTrackingProfile.WALKING, stringResource(R.string.profile_walking)),
            GeoVaultSelectOption(TrackerTrackingProfile.BIKING, stringResource(R.string.profile_biking)),
            GeoVaultSelectOption(TrackerTrackingProfile.DRIVING, stringResource(R.string.profile_driving)),
            GeoVaultSelectOption(TrackerTrackingProfile.CUSTOM, stringResource(R.string.profile_custom)),
        )
    }
    val distanceUnit = stringResource(if (state.usesImperialUnits) R.string.unit_ft else R.string.unit_m)

    Box(modifier = modifier.fillMaxSize()) {
        GeoVaultSubViewScaffold(
            modifier = Modifier.fillMaxSize(),
            title = stringResource(R.string.nav_settings),
            onClose = onClose,
            closeContentDescription = stringResource(R.string.close),
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .geoVaultKeyboardAwareVerticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                ) {
        if (state.trackerLoadState == TrackerSettingsLoadState.Loading) {
            Text(
                text = stringResource(R.string.settings_tracker_loading),
                style = MaterialTheme.typography.body2,
            )
        } else if (state.trackerLoadState == TrackerSettingsLoadState.Error) {
            Text(
                text = stringResource(R.string.settings_tracker_error),
                style = MaterialTheme.typography.body2,
            )
        }

        val trackerSettings = state.trackerSettings
        GeoVaultToggleHelpCard(
            checked = trackerSettings.sendExtendedData,
            onCheckedChange = { if (!shouldIgnoreSettingChange()) onSendExtendedData(it) },
            title = stringResource(R.string.extended_params_label),
            helpText = stringResource(R.string.extended_params_help_text),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        GeoVaultToggleHelpCard(
            checked = trackerSettings.significantDataOnly,
            onCheckedChange = {
                if (!shouldIgnoreSettingChange() && state.significantMotionSensorAvailable) {
                    onSignificantMotionOnly(it)
                }
            },
            title = stringResource(R.string.significant_motion_label),
            helpText = stringResource(R.string.significant_motion_help_text),
            modifier = Modifier.padding(vertical = 6.dp),
            enabled = state.significantMotionSensorAvailable,
        )
        GeoVaultToggleHelpCard(
            checked = trackerSettings.startOnBoot,
            onCheckedChange = { if (!shouldIgnoreSettingChange()) onStartOnBoot(it) },
            title = stringResource(R.string.start_on_boot_label),
            helpText = stringResource(R.string.start_on_boot_help_text),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        GeoVaultToggleHelpCard(
            checked = trackerSettings.startTrackingOnLaunch,
            onCheckedChange = { if (!shouldIgnoreSettingChange()) onStartOnLaunch(it) },
            title = stringResource(R.string.start_tracking_on_launch_label),
            helpText = stringResource(R.string.start_tracking_on_launch_help_text),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        GeoVaultToggleHelpCard(
            checked = trackerSettings.keepScreenOnWhileViewingMap,
            onCheckedChange = { if (!shouldIgnoreSettingChange()) onKeepScreenOnMap(it) },
            title = stringResource(R.string.keep_screen_on_while_viewing_map_label),
            helpText = stringResource(R.string.keep_screen_on_while_viewing_map_help_text),
            modifier = Modifier.padding(vertical = 6.dp),
        )

        GeoVaultSecondaryButton(
            text = stringResource(R.string.hidden_trackers),
            onClick = {
                onRefreshHiddenTrackerItems()
                showHiddenTrackersOverlay = true
            },
            tooltip = stringResource(R.string.tooltip_settings_hidden_trackers),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 16.dp),
        )
        GeoVaultSecondaryButton(
            text = stringResource(R.string.show_all_trackers_in_settings),
            onClick = onOpenAllTrackersOnMap,
            tooltip = stringResource(R.string.tooltip_settings_view_all_map),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        )

        Divider(
            color = geoVaultHairlineDividerColor(),
            thickness = 1.dp,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        GeoVaultToggleHelpCard(
            checked = trackerSettings.autoTrackingMode,
            onCheckedChange = {
                if (shouldIgnoreSettingChange()) return@GeoVaultToggleHelpCard
                if (it) {
                    isBindingSettings = true
                    intervalText = autoText
                    distanceText = autoText
                    accuracyText = autoText
                    isBindingSettings = false
                }
                onAutoTrackingMode(it)
            },
            title = stringResource(R.string.auto_tracking_label),
            helpText = stringResource(R.string.auto_tracking_help_text),
            modifier = Modifier.padding(vertical = 6.dp),
        )

        Text(
            text = stringResource(R.string.tracking_profile_label),
            style = MaterialTheme.typography.subtitle2,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        GeoVaultSelectField(
            selectedValue = selectedProfile,
            options = profileOptions,
            onValueSelected = { profile ->
                selectedProfile = profile
                if (profile != TrackerTrackingProfile.CUSTOM) {
                    val params = TrackingLocationPolicy.getProfileParams(profile.index)
                    isUpdatingFromProfile = true
                    intervalText = params.first.toString()
                    distanceText = SettingsMeasurementPolicy.metersToDisplayText(
                        meters = params.second,
                        usesImperial = state.usesImperialUnits
                    )
                    accuracyText = SettingsMeasurementPolicy.metersToDisplayText(
                        meters = params.third,
                        usesImperial = state.usesImperialUnits
                    )
                    isUpdatingFromProfile = false
                }
                onTrackingProfileSelected(profile)
            },
            dialogTitle = stringResource(R.string.tracking_profile_label),
            label = null,
            enabled = !trackerSettings.autoTrackingMode,
            placeholder = profileDisplay,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.tracking_profile_help_text),
            color = geoVaultContentSecondaryColor(),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.logging_interval_label),
                style = MaterialTheme.typography.subtitle2,
                modifier = Modifier.weight(1f),
            )
            GeoVaultIconButton(
                onClick = { showLoggingHelpDialog = true },
                tooltip = stringResource(R.string.tooltip_settings_logging_help),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.logging_help_button),
                    tint = GeoVaultColorTokens.MainBlue,
                )
            }
        }
        SettingsNumericInput(
            value = intervalText,
            onValueChange = {
                intervalText = it
                maybePromoteCustomFromInterval(it)
            },
            enabled = !trackerSettings.autoTrackingMode,
            onDone = { commitInterval() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    val lostFocus = intervalFocused && !it.isFocused
                    intervalFocused = it.isFocused
                    if (lostFocus) commitInterval()
                },
        )
        Text(
            text = stringResource(R.string.logging_interval_help_text),
            color = geoVaultContentSecondaryColor(),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        Text(
            text = stringResource(R.string.distance_filter_label, distanceUnit),
            style = MaterialTheme.typography.subtitle2,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SettingsNumericInput(
            value = distanceText,
            onValueChange = {
                distanceText = it
                maybePromoteCustomFromDistance(it)
            },
            enabled = !trackerSettings.autoTrackingMode,
            onDone = { commitDistance() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    val lostFocus = distanceFocused && !it.isFocused
                    distanceFocused = it.isFocused
                    if (lostFocus) commitDistance()
                },
        )
        Text(
            text = stringResource(R.string.distance_filter_help_text),
            color = geoVaultContentSecondaryColor(),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        Text(
            text = stringResource(R.string.accuracy_filter_label, distanceUnit),
            style = MaterialTheme.typography.subtitle2,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SettingsNumericInput(
            value = accuracyText,
            onValueChange = {
                accuracyText = it
                maybePromoteCustomFromAccuracy(it)
            },
            enabled = !trackerSettings.autoTrackingMode,
            onDone = { commitAccuracy() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    val lostFocus = accuracyFocused && !it.isFocused
                    accuracyFocused = it.isFocused
                    if (lostFocus) commitAccuracy()
                },
        )
        Text(
            text = stringResource(R.string.accuracy_filter_help_text),
            color = geoVaultContentSecondaryColor(),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        Text(
            text = stringResource(R.string.tracking_settings_apply_after_restart),
            color = geoVaultContentSecondaryColor(),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        Divider(
            color = geoVaultHairlineDividerColor(),
            thickness = 1.dp,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        GeoVaultToggleHelpCard(
            checked = trackerSettings.lowAccuracyFallbackEnabled,
            onCheckedChange = {
                if (!shouldIgnoreSettingChange()) onLowAccuracyFallbackEnabled(it)
            },
            title = stringResource(R.string.low_accuracy_fallback_label),
            helpText = stringResource(R.string.low_accuracy_fallback_help_text),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Text(
            text = stringResource(R.string.low_accuracy_fallback_timeout_label),
            style = MaterialTheme.typography.subtitle2,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SettingsNumericInput(
            value = lowAccuracyTimeoutText,
            onValueChange = { lowAccuracyTimeoutText = it },
            enabled = trackerSettings.lowAccuracyFallbackEnabled,
            onDone = { commitLowAccuracyTimeout() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    val lostFocus = timeoutFocused && !it.isFocused
                    timeoutFocused = it.isFocused
                    if (lostFocus) commitLowAccuracyTimeout()
                },
        )
        Text(
            text = stringResource(R.string.low_accuracy_fallback_timeout_help_text),
            color = geoVaultContentSecondaryColor(),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        if (!state.isLoggedIn) {
            Divider(
                color = geoVaultHairlineDividerColor(),
                thickness = 1.dp,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Text(
                text = stringResource(R.string.server_url_label),
                style = MaterialTheme.typography.subtitle2,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            GeoVaultInput(
                value = state.serverUrl,
                onValueChange = onServerUrlChanged,
                placeholder = "geovault.example.com",
                enabled = true,
                readOnly = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )
            val connectState = rememberConnectingButtonState(
                isConnecting = state.isConnecting,
                onConnect = onConnect,
            )
            GeoVaultPrimaryButton(
                text = if (connectState.isEffectivelyConnecting) "Connecting..." else stringResource(R.string.connect_account),
                onClick = { connectState.onClick() },
                enabled = !connectState.isEffectivelyConnecting,
                tooltip = stringResource(R.string.tooltip_settings_connect),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
            if (!state.infoMessage.isNullOrBlank() && !connectState.isEffectivelyConnecting) {
                Text(
                    text = state.infoMessage,
                    color = geoVaultContentSecondaryColor(),
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        } else {
            val loggedInEmail = state.loggedInText
                .removePrefix("Logged in as")
                .trim()
                .ifBlank { "Authenticated User" }
            GeoVaultServerConfigBlock(
                serverUrl = state.serverUrl,
                loggedInEmail = loggedInEmail,
                onDisconnectConfirmed = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                title = stringResource(R.string.server_settings_title),
                disconnectButtonText = stringResource(R.string.disconnect),
                disconnectButtonTooltip = stringResource(R.string.tooltip_settings_disconnect),
            )
        }
            }
            TrackerParamsOverlayLayer()
        }
        }
        if (showHiddenTrackersOverlay) {
            HiddenTrackersSubView(
                items = state.hiddenTrackerItems,
                isLoading = state.isHiddenTrackerItemsLoading,
                onDismiss = { showHiddenTrackersOverlay = false },
                onRefresh = onRefreshHiddenTrackerItems,
                onUnhideItem = onUnhideTrackerItem,
                onUnhideAll = onUnhideAllTrackerItems,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showLoggingHelpDialog) {
        GeoVaultInfoDialog(
            title = stringResource(R.string.logging_help_title),
            onDismissRequest = { showLoggingHelpDialog = false },
            closeButtonText = stringResource(R.string.close),
        ) {
            Text(text = stringResource(R.string.logging_help_message))
        }
    }
}

@Composable
private fun SettingsNumericInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldBackground = MaterialTheme.colors.surface
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            backgroundColor = fieldBackground,
            focusedBorderColor = GeoVaultColorTokens.MainBlue,
            unfocusedBorderColor = GeoVaultColorTokens.MainBlue,
            focusedLabelColor = GeoVaultColorTokens.MainBlue,
            unfocusedLabelColor = GeoVaultColorTokens.MainBlue,
            disabledTextColor = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            disabledBorderColor = GeoVaultColorTokens.MainBlue,
            disabledLabelColor = GeoVaultColorTokens.MainBlue.copy(alpha = 0.6f),
            disabledPlaceholderColor = geoVaultContentSecondaryColor().copy(alpha = 0.6f),
            disabledTrailingIconColor = Color.Unspecified,
            disabledLeadingIconColor = Color.Unspecified,
        ),
    )
}

@Composable
private fun HiddenTrackersSubView(
    items: List<HiddenTrackerItem>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onUnhideItem: (HiddenTrackerItem) -> Unit,
    onUnhideAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showUnhideAllConfirm by remember { mutableStateOf(false) }
    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        onBack = {
            onDismiss()
            true
        },
    )
    val scrollState = rememberScrollState()
    GeoVaultSubViewScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.hidden_trackers),
        onClose = onDismiss,
        onLeaveComposition = onDismiss,
        headerExtras = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GeoVaultSecondaryButton(
                    text = stringResource(R.string.show_all),
                    onClick = { showUnhideAllConfirm = true },
                    enabled = !isLoading && items.isNotEmpty(),
                    tooltip = stringResource(R.string.tooltip_hidden_show_all),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { innerPadding ->
        GeoVaultPullRefreshLoadingContainer(
            refreshing = isLoading,
            showBlockingLoader = isLoading,
            onRefresh = onRefresh,
            loadingText = stringResource(R.string.loading_trackers),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(20.dp),
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.hidden_trackers_empty),
                        color = geoVaultContentSecondaryColor(),
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                } else {
                    val trackers = items.filter { it.type == HiddenTrackerItemType.TRACKER }
                    val groups = items.filter { it.type == HiddenTrackerItemType.GROUP }
                    if (trackers.isNotEmpty() && groups.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.hidden_list_section_trackers),
                            color = geoVaultContentSecondaryColor(),
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                    trackers.forEach { item ->
                        HiddenTrackerRow(item = item, onUnhideItem = onUnhideItem, isLoading = isLoading)
                    }
                    if (groups.isNotEmpty()) {
                        if (trackers.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.hidden_groups),
                                color = geoVaultContentSecondaryColor(),
                                style = MaterialTheme.typography.caption,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                            )
                        }
                        groups.forEach { item ->
                            HiddenTrackerRow(item = item, onUnhideItem = onUnhideItem, isLoading = isLoading)
                        }
                    }
                }
            }
        }
    }
    if (showUnhideAllConfirm) {
        GeoVaultConfirmationDialog(
            title = stringResource(R.string.hidden_unhide_all_confirm_title),
            message = stringResource(R.string.hidden_unhide_all_confirm_message),
            onConfirm = {
                showUnhideAllConfirm = false
                onUnhideAll()
            },
            onCancel = { showUnhideAllConfirm = false },
            confirmText = stringResource(R.string.show_all),
            cancelText = stringResource(R.string.cancel_button),
        )
    }
}

@Composable
private fun HiddenTrackerRow(
    item: HiddenTrackerItem,
    onUnhideItem: (HiddenTrackerItem) -> Unit,
    isLoading: Boolean,
) {
    val rowShape = RoundedCornerShape(12.dp)
    val iconRes = when (item.type) {
        HiddenTrackerItemType.TRACKER -> R.drawable.ic_chevron_track
        HiddenTrackerItemType.GROUP -> R.drawable.ic_groups
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(rowShape)
            .clickable(enabled = !isLoading) { onUnhideItem(item) }
            .background(
                color = MaterialTheme.colors.surface,
                shape = rowShape,
            )
            .border(
                width = 1.dp,
                color = GeoVaultColorTokens.MainBlue,
                shape = rowShape,
            )
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = GeoVaultColorTokens.MainBlue,
            modifier = Modifier
                .size(18.dp),
        )
        Text(
            text = item.name,
            style = MaterialTheme.typography.body1.copy(fontSize = 16.sp),
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        GeoVaultIconButton(
            onClick = { onUnhideItem(item) },
            enabled = !isLoading,
            modifier = Modifier.size(40.dp),
            tooltip = stringResource(R.string.tooltip_hidden_show_one),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_eye),
                contentDescription = stringResource(R.string.show_button),
                tint = GeoVaultColorTokens.MainBlue,
            )
        }
    }
}
