package com.geovault.tracker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.UnitUtils
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.presentation.HomeLayoutMode
import com.geovault.tracker.presentation.HomeLayoutSizingInput
import com.geovault.tracker.presentation.HomeLayoutSizingPolicy
import com.geovault.tracker.presentation.HomeUiState
import com.geovault.tracker.presentation.HomeViewModel
import com.geovault.tracker.services.TrackingUiStatus
import kotlinx.coroutines.delay

private const val FEET_PER_METER = 3.28084f
private const val MAX_DISPLAY_ACCURACY_FEET = 1500f
private const val MAX_DISPLAY_ACCURACY_METERS = MAX_DISPLAY_ACCURACY_FEET / FEET_PER_METER

@Composable
fun HomeScreen(
    isAuthenticated: Boolean,
    isServerAccessible: Boolean,
    isPreparingToTrack: Boolean,
    serverUrl: String,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    isConnecting: Boolean,
    onOpenSettings: () -> Unit,
    infoMessage: String?,
    onClearInfoMessage: () -> Unit,
    onRequestStartTracking: () -> Unit,
    onRequestStopTracking: () -> Unit,
    onRequestManualPoint: () -> Unit,
    onRequestTrackerParams: (TrackerParamsUiModel) -> Unit,
) {
    val homeViewModel: HomeViewModel = viewModel()
    val homeState by homeViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showStopTrackingConfirm by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                homeViewModel.refreshPermissionSnapshot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val foregroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        homeViewModel.refreshPermissionSnapshot()
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        homeViewModel.refreshPermissionSnapshot()
    }
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        homeViewModel.refreshPermissionSnapshot()
    }

    TrackerTabPlaceholderScreen(
        title = stringResource(R.string.home_title),
        placeholderText = stringResource(R.string.home_placeholder_legacy),
        isAuthenticated = isAuthenticated,
        serverUrl = serverUrl,
        onAuthServerUrlChanged = onAuthServerUrlChanged,
        onAuthConnect = onAuthConnect,
        isConnecting = isConnecting,
        onOpenSettings = onOpenSettings,
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        authenticatedMainContent = {
            val perms = homeState.permissions
            val hasAllRequiredPermissions = perms.hasForegroundLocation &&
                perms.hasBackgroundLocation &&
                perms.hasPostNotifications &&
                perms.hasBatteryOptimizationExemption &&
                perms.hasExactAlarmPermission
            val trackerParamsModel = homeTrackerParamsModelOrNull(homeState)
            val showInlineButtons = homeState.isTracking && homeState.selectedTrackerId.isNotBlank()
            val isRunningOrPreparing = homeState.isTracking || isPreparingToTrack

            Box(modifier = Modifier.fillMaxSize()) {
                if (!hasAllRequiredPermissions) {
                    PermissionsContainer(
                        hasForegroundLocation = perms.hasForegroundLocation,
                        hasBackgroundLocation = perms.hasBackgroundLocation,
                        hasPostNotifications = perms.hasPostNotifications,
                        hasBatteryExemption = perms.hasBatteryOptimizationExemption,
                        hasExactAlarm = perms.hasExactAlarmPermission,
                        onGrantForeground = {
                            foregroundLocationLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onGrantBackground = {
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        },
                        onGrantNotifications = {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onGrantBattery = { openBatteryOptimizationSettings(context) },
                        onGrantExactAlarm = { openExactAlarmSettings(context) },
                    )
                } else {
                    TrackingContainer(
                        state = homeState,
                        isPreparingToTrack = isPreparingToTrack,
                        showInlineButtons = showInlineButtons,
                        canOpenParams = trackerParamsModel != null,
                        onStartStop = {
                            if (isRunningOrPreparing) {
                                showStopTrackingConfirm = true
                            } else {
                                onRequestStartTracking()
                            }
                        },
                        onParams = { trackerParamsModel?.let(onRequestTrackerParams) },
                        onManualPoint = onRequestManualPoint,
                    )
                }
                if (!isServerAccessible && !isConnecting) {
                    ServerFailureOverlay(modifier = Modifier.fillMaxSize())
                }
            }
        },
    )
    if (showStopTrackingConfirm) {
        GeoVaultConfirmationDialog(
            title = stringResource(R.string.home_stop_confirm_title),
            message = stringResource(R.string.home_stop_confirm_message),
            onConfirm = {
                showStopTrackingConfirm = false
                onRequestStopTracking()
            },
            onCancel = { showStopTrackingConfirm = false },
            confirmText = stringResource(R.string.stop_tracking),
            cancelText = stringResource(R.string.trackers_dialog_cancel),
        )
    }
}

@Composable
private fun PermissionsContainer(
    hasForegroundLocation: Boolean,
    hasBackgroundLocation: Boolean,
    hasPostNotifications: Boolean,
    hasBatteryExemption: Boolean,
    hasExactAlarm: Boolean,
    onGrantForeground: () -> Unit,
    onGrantBackground: () -> Unit,
    onGrantNotifications: () -> Unit,
    onGrantBattery: () -> Unit,
    onGrantExactAlarm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permissions_required_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = GeoVaultColorTokens.TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.permissions_required_message),
            fontSize = 14.sp,
            color = GeoVaultColorTokens.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        if (!hasForegroundLocation) {
            GeoVaultPrimaryButton(
                text = stringResource(R.string.grant_location_permission),
                onClick = onGrantForeground,
                tooltip = stringResource(R.string.tooltip_grant_location),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (hasForegroundLocation && !hasBackgroundLocation) {
            GeoVaultPrimaryButton(
                text = stringResource(R.string.grant_background_location),
                onClick = onGrantBackground,
                tooltip = stringResource(R.string.tooltip_grant_background_location),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (!hasPostNotifications) {
            GeoVaultPrimaryButton(
                text = stringResource(R.string.grant_notification_permission),
                onClick = onGrantNotifications,
                tooltip = stringResource(R.string.tooltip_grant_notifications),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (!hasBatteryExemption) {
            GeoVaultPrimaryButton(
                text = stringResource(R.string.disable_battery_optimization),
                onClick = onGrantBattery,
                tooltip = stringResource(R.string.tooltip_grant_battery),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (!hasExactAlarm) {
            GeoVaultPrimaryButton(
                text = stringResource(R.string.grant_exact_alarm_permission),
                onClick = onGrantExactAlarm,
                tooltip = stringResource(R.string.tooltip_grant_exact_alarm),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrackingContainer(
    state: HomeUiState,
    isPreparingToTrack: Boolean,
    showInlineButtons: Boolean,
    canOpenParams: Boolean,
    onStartStop: () -> Unit,
    onParams: () -> Unit,
    onManualPoint: () -> Unit,
) {
    val nowMs by rememberHomeTicker(isEnabled = state.isTracking)
    val statusText = if (isPreparingToTrack) stringResource(R.string.preparing) else stringResource(homeStatusLabelRes(state.trackingUiStatus))
    val trackerName = if (state.selectedTrackerDisplayName.isBlank()) {
        stringResource(R.string.no_tracker_selected).uppercase()
    } else {
        state.selectedTrackerDisplayName
    }
    val trackerTextColor = if (state.selectedTrackerDisplayName.isBlank() && state.selectedTrackerId.isBlank()) {
        GeoVaultColorTokens.Error
    } else {
        GeoVaultColorTokens.TextSecondary
    }
    val useImperial = UnitUtils.usesImperialUnitsDefault(androidx.compose.ui.platform.LocalContext.current)
    val accuracy = formatAccuracyPresentation(state, useImperial)
    val isRunningOrPreparing = state.isTracking || isPreparingToTrack
    val density = LocalDensity.current
    var layoutMode by remember { mutableStateOf(HomeLayoutMode.NORMAL) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableHeightPx = with(density) { maxHeight.roundToPx() }
        val occlusionPx = maxOf(
            WindowInsets.ime.getBottom(density),
            WindowInsets.navigationBars.getBottom(density)
        )
        LaunchedEffect(availableHeightPx, occlusionPx) {
            layoutMode = HomeLayoutSizingPolicy.resolveMode(
                HomeLayoutSizingInput(
                    previousMode = layoutMode,
                    availableHeightPx = availableHeightPx,
                    occlusionPx = occlusionPx,
                )
            )
        }
        val compactLayout = layoutMode != HomeLayoutMode.NORMAL
        val hideRadar = layoutMode == HomeLayoutMode.COMPACT_HIDE_RADAR
        val topPadding = if (compactLayout) 12.dp else 32.dp
        val bottomPadding = if (compactLayout) 4.dp else 16.dp
        val radarContainerSize = if (compactLayout) 132.dp else 180.dp
        val radarIconSize = if (compactLayout) 102.dp else 140.dp
        val radarBottom = if (compactLayout) 8.dp else 24.dp
        val inlineTop = if (compactLayout) 8.dp else 12.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 32.dp, end = 32.dp, top = topPadding, bottom = bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!hideRadar) {
                Box(modifier = Modifier.size(radarContainerSize), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_radar_dish),
                        contentDescription = null,
                        tint = if (state.isTracking) GeoVaultColorTokens.MainYellow else GeoVaultColorTokens.PrimaryBlue,
                        modifier = Modifier.size(radarIconSize),
                    )
                }
                Spacer(modifier = Modifier.height(radarBottom))
            }
            Text(
                text = statusText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = homeStatusColor(state.trackingUiStatus, isPreparingToTrack),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = trackerName, fontSize = 16.sp, color = trackerTextColor)
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_elapsed), formatDurationMs(state.isTracking, state.sessionStartTimeMs))
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_sent), if (state.isTracking) state.pointsSentThisSession.toString() else "\u2014")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_last), formatLastSentAgo(state, nowMs))
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_queued), if (state.isTracking) state.queuedPointsVisible.toString() else "\u2014")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_distance), formatDistanceText(state.sessionTotalDistanceMeters, useImperial, state.isTracking))
                    StatCard(
                        Modifier.weight(1f),
                        stringResource(R.string.stat_label_accuracy),
                        accuracy.text,
                        if (accuracy.isError) GeoVaultColorTokens.Error else GeoVaultColorTokens.TextPrimary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            GeoVaultPrimaryButton(
                text = if (isRunningOrPreparing) stringResource(R.string.stop_tracking) else stringResource(R.string.start_tracking),
                onClick = onStartStop,
                tooltip = stringResource(R.string.tooltip_start_stop_tracking),
                modifier = Modifier.width(200.dp).height(64.dp),
            )
            Spacer(modifier = Modifier.height(inlineTop))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallIconActionButton(
                    iconRes = R.drawable.ic_params,
                    contentDescription = stringResource(R.string.map_tracker_info_view_params_content_description),
                    visible = showInlineButtons,
                    enabled = showInlineButtons && canOpenParams,
                    onClick = onParams,
                )
                SmallIconActionButton(
                    iconRes = R.drawable.ic_send_point,
                    contentDescription = stringResource(R.string.home_manual_send_point_content_description),
                    visible = showInlineButtons,
                    enabled = showInlineButtons,
                    onClick = onManualPoint,
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = GeoVaultColorTokens.TextPrimary,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, GeoVaultColorTokens.PrimaryBlue),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = GeoVaultColorTokens.TextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                color = valueColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SmallIconActionButton(
    iconRes: Int,
    contentDescription: String,
    visible: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .alpha(if (visible) 1f else 0f),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, GeoVaultColorTokens.PrimaryBlue),
        elevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = GeoVaultColorTokens.PrimaryBlue,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ServerFailureOverlay(modifier: Modifier = Modifier) {
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
            modifier = Modifier.width(280.dp),
            shape = MaterialTheme.shapes.medium,
            color = androidx.compose.ui.graphics.Color(0xFFFFF3F3),
            border = BorderStroke(3.dp, GeoVaultColorTokens.Error),
            elevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_radar_dish),
                    contentDescription = null,
                    tint = GeoVaultColorTokens.Error,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.server_connection_error_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = GeoVaultColorTokens.Error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.server_connection_error_message),
                    fontSize = 14.sp,
                    color = GeoVaultColorTokens.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun rememberHomeTicker(isEnabled: Boolean): androidx.compose.runtime.State<Long> {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isEnabled) {
        if (!isEnabled) {
            now.value = System.currentTimeMillis()
            return@LaunchedEffect
        }
        while (true) {
            now.value = System.currentTimeMillis()
            delay(1000L)
        }
    }
    return now
}

private fun openBatteryOptimizationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openExactAlarmSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openLocationSourceSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun homeStatusLabelRes(status: TrackingUiStatus): Int {
    return when (status) {
        TrackingUiStatus.NOT_TRACKING -> R.string.not_tracking
        TrackingUiStatus.WAITING_FOR_GPS -> R.string.waiting_for_gps_reenabled
        TrackingUiStatus.LOCKING -> R.string.locking
        TrackingUiStatus.TRACKING_ACTIVE -> R.string.tracking_active
    }
}

private fun homeStatusColor(status: TrackingUiStatus, isPreparingToTrack: Boolean): androidx.compose.ui.graphics.Color {
    if (isPreparingToTrack) return GeoVaultColorTokens.PrimaryBlue
    return when (status) {
        TrackingUiStatus.NOT_TRACKING -> GeoVaultColorTokens.PrimaryBlue
        TrackingUiStatus.WAITING_FOR_GPS -> GeoVaultColorTokens.Error
        TrackingUiStatus.LOCKING -> GeoVaultColorTokens.MainYellow
        TrackingUiStatus.TRACKING_ACTIVE -> GeoVaultColorTokens.MainYellow
    }
}

private fun formatDurationMs(isTracking: Boolean, sessionStartTimeMs: Long): String {
    if (!isTracking || sessionStartTimeMs <= 0L) return "\u2014"
    val totalSec = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatLastSentAgo(state: HomeUiState, nowMs: Long): String {
    if (!state.isTracking) return "\u2014"
    val lastPointSentAtMs = state.lastPointSentAtMs
    if (lastPointSentAtMs <= 0L) return "now"
    val elapsedMs = nowMs - lastPointSentAtMs
    val raw = when {
        elapsedMs < 10_000L -> "now"
        elapsedMs < DateUtils.MINUTE_IN_MILLIS -> "${elapsedMs / DateUtils.SECOND_IN_MILLIS}s"
        elapsedMs < DateUtils.HOUR_IN_MILLIS -> "${elapsedMs / DateUtils.MINUTE_IN_MILLIS}m"
        elapsedMs < DateUtils.DAY_IN_MILLIS -> "${elapsedMs / DateUtils.HOUR_IN_MILLIS}h"
        else -> "${elapsedMs / DateUtils.DAY_IN_MILLIS}d"
    }
    return if (raw == "now") raw else "-$raw"
}

private fun formatDistanceText(
    meters: Float,
    imperial: Boolean,
    isTracking: Boolean,
): String {
    if (!isTracking) return "\u2014"
    if (imperial) {
        val feet = meters * FEET_PER_METER
        return if (feet < 5280f) {
            "%d ft".format(feet.toInt())
        } else {
            "%.2f mi".format(feet / 5280f)
        }
    }
    return if (meters < 1000f) {
        "%d m".format(meters.toInt())
    } else {
        "%.1f km".format(meters / 1000f)
    }
}

private data class HomeAccuracyPresentation(
    val text: String,
    val isError: Boolean,
)

private fun formatAccuracyPresentation(state: HomeUiState, imperial: Boolean): HomeAccuracyPresentation {
    if (!state.isTracking) return HomeAccuracyPresentation(text = "\u2014", isError = false)
    val accuracy = state.lastAccuracyMeters
    if (accuracy == null || accuracy > MAX_DISPLAY_ACCURACY_METERS) {
        return HomeAccuracyPresentation(text = "-", isError = true)
    }
    val value = if (imperial) (accuracy * FEET_PER_METER).toInt() else accuracy.toInt()
    val text = if (imperial) {
        "\u00B1%d ft".format(value)
    } else {
        "\u00B1%d m".format(value)
    }
    return HomeAccuracyPresentation(
        text = text,
        isError = accuracy > state.effectiveAccuracyThresholdMeters
    )
}

private fun homeTrackerParamsModelOrNull(state: HomeUiState): TrackerParamsUiModel? {
    val lat = state.lastTrackedLatitude ?: return null
    val lon = state.lastTrackedLongitude ?: return null
    val trackerName = state.selectedTrackerDisplayName.ifBlank { state.selectedTrackerId }
    if (trackerName.isBlank()) return null
    val lastUpdateMs = when {
        state.lastTrackedTimestampMs > 0L -> state.lastTrackedTimestampMs
        state.lastPointSentAtMs > 0L -> state.lastPointSentAtMs
        else -> null
    }
    return TrackerParamsUiModel(
        trackerName = trackerName,
        latitude = lat,
        longitude = lon,
        lastUpdatedMs = lastUpdateMs,
        accuracyMeters = state.lastAccuracyMeters,
        isOwned = true,
    )
}
