package com.geovault.tracker.ui

import android.Manifest
import android.graphics.Rect
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.geovault.common.ui.components.GeoVaultClickableWithTooltip
import com.geovault.common.ui.components.GeoVaultConfirmationDialog
import com.geovault.common.ui.components.GeoVaultInfoDialog
import com.geovault.common.ui.components.GeoVaultNavTabShell
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.time.rememberNowMs
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.params.TrackerParamsSeed
import com.geovault.tracker.R
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.presentation.HomeLayoutMode
import com.geovault.tracker.presentation.HomeLayoutSizingInput
import com.geovault.tracker.presentation.HomeLayoutSizingPolicy
import com.geovault.tracker.presentation.HomeUiState
import com.geovault.tracker.presentation.HomeViewModel
import com.geovault.tracker.services.TrackingUiStatus
import com.geovault.tracker.ui.time.HomeElapsedTimeFormat

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
    onRequestTrackerParams: (TrackerParamsRouteArgs) -> Unit,
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

    var showPreciseLocationRequiredDialog by rememberSaveable { mutableStateOf(false) }

    val foregroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        homeViewModel.refreshPermissionSnapshot()
        if (TrackingPermissionGate.hasAnyLocationPermission(context) &&
            !TrackingPermissionGate.hasLocationPermission(context)
        ) {
            showPreciseLocationRequiredDialog = true
        }
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

    GeoVaultNavTabShell(
        title = stringResource(R.string.home_title),
        placeholderText = stringResource(R.string.home_placeholder),
        isAuthenticated = isAuthenticated,
        serverUrl = serverUrl,
        onAuthServerUrlChanged = onAuthServerUrlChanged,
        onAuthConnect = onAuthConnect,
        isConnecting = isConnecting,
        onOpenSettings = onOpenSettings,
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        connectButtonTooltip = stringResource(R.string.tooltip_settings_connect),
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
            val trackerParamsArgs = homeTrackerParamsRouteArgsOrNull(homeState)
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
                            if (TrackingPermissionGate.hasAnyLocationPermission(context)) {
                                showPreciseLocationRequiredDialog = true
                            } else {
                                foregroundLocationLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            }
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
                        canOpenParams = trackerParamsArgs != null,
                        onStartStop = {
                            if (isRunningOrPreparing) {
                                showStopTrackingConfirm = true
                            } else {
                                onRequestStartTracking()
                            }
                        },
                        onParams = { trackerParamsArgs?.let(onRequestTrackerParams) },
                        onManualPoint = onRequestManualPoint,
                    )
                }
                if (!isServerAccessible && !isConnecting) {
                    ServerFailureOverlay(modifier = Modifier.fillMaxSize())
                }
            }
        },
        tabOverlay = { TrackerParamsOverlayLayer() },
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
    if (showPreciseLocationRequiredDialog) {
        GeoVaultInfoDialog(
            title = stringResource(R.string.precise_location_required_title),
            onDismissRequest = {
                showPreciseLocationRequiredDialog = false
                openLocationPermissionSettings(context)
            },
            closeButtonText = stringResource(R.string.open_settings),
        ) {
            Text(stringResource(R.string.precise_location_required_message))
        }
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
    val context = LocalContext.current
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
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.permissions_required_message),
            fontSize = 14.sp,
            color = geoVaultContentSecondaryColor(),
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
        if (!hasBackgroundLocation) {
            GeoVaultPrimaryButton(
                text = stringResource(R.string.grant_background_location),
                onClick = {
                    if (hasForegroundLocation) {
                        onGrantBackground()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.grant_location_permission_first),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
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
    val nowMs by rememberNowMs()
    val statusText = if (isPreparingToTrack) stringResource(R.string.preparing) else stringResource(homeStatusLabelRes(state.trackingUiStatus))
    val trackerName = if (state.selectedTrackerDisplayName.isBlank()) {
        stringResource(R.string.no_tracker_selected).uppercase()
    } else {
        state.selectedTrackerDisplayName
    }
    val trackerTextColor = if (state.selectedTrackerDisplayName.isBlank() && state.selectedTrackerId.isBlank()) {
        GeoVaultColorTokens.Error
    } else {
        geoVaultContentSecondaryColor()
    }
    val useImperial = UnitUtils.usesImperialUnitsDefault(androidx.compose.ui.platform.LocalContext.current)
    val accuracy = formatAccuracyPresentation(state, useImperial)
    val isRunningOrPreparing = state.isTracking || isPreparingToTrack
    val density = LocalDensity.current
    val view = LocalView.current
    var layoutMode by remember { mutableStateOf(HomeLayoutMode.NORMAL) }
    var inlineRowVisibleFrameOverlapPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableHeightPx = with(density) { maxHeight.roundToPx() }
        val windowInsetOcclusionPx = maxOf(
            WindowInsets.ime.getBottom(density),
            WindowInsets.navigationBars.getBottom(density),
        )
        val occlusionPx = maxOf(windowInsetOcclusionPx, inlineRowVisibleFrameOverlapPx)
        LaunchedEffect(availableHeightPx, occlusionPx, density.density, density.fontScale) {
            layoutMode = HomeLayoutSizingPolicy.resolveMode(
                HomeLayoutSizingInput(
                    density = density,
                    previousMode = layoutMode,
                    availableHeightPx = availableHeightPx,
                    occlusionPx = occlusionPx,
                ),
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
                        tint = if (state.isTracking) GeoVaultColorTokens.MainYellow else GeoVaultColorTokens.MainBlue,
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
            if (state.sparseTrackingEnabled) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.sparse_tracking_label),
                    fontSize = 14.sp,
                    color = geoVaultContentSecondaryColor(),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = trackerName, fontSize = 16.sp, color = trackerTextColor)
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_elapsed), formatDurationMs(state.isTracking, state.sessionStartTimeMs, nowMs))
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_sent), if (state.isTracking) state.pointsSentThisSession.toString() else "\u2014")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_last), formatHomeLastAgo(state, nowMs))
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_queued), if (state.isTracking) state.queuedPointsVisible.toString() else "\u2014")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatCard(Modifier.weight(1f), stringResource(R.string.stat_label_distance), formatDistanceText(state.sessionTotalDistanceMeters, useImperial, state.isTracking))
                    StatCard(
                        Modifier.weight(1f),
                        stringResource(R.string.stat_label_accuracy),
                        accuracy.text,
                        if (accuracy.isError) GeoVaultColorTokens.Error else null,
                    )
                }
            }
            Spacer(modifier = Modifier.height(if (compactLayout) 16.dp else 24.dp))
            GeoVaultPrimaryButton(
                text = if (isRunningOrPreparing) stringResource(R.string.stop_tracking) else stringResource(R.string.start_tracking),
                onClick = onStartStop,
                tooltip = stringResource(R.string.tooltip_start_stop_tracking),
                modifier = Modifier.width(200.dp).height(64.dp),
            )
            Spacer(modifier = Modifier.height(inlineTop))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onGloballyPositioned { coords ->
                    if (!coords.isAttached) return@onGloballyPositioned
                    val pos = coords.positionInWindow()
                    val rowBottom = (pos.y + coords.size.height).toInt()
                    val visible = Rect()
                    view.rootView.getWindowVisibleDisplayFrame(visible)
                    inlineRowVisibleFrameOverlapPx = (rowBottom - visible.bottom).coerceAtLeast(0)
                },
            ) {
                SmallIconActionButton(
                    iconRes = R.drawable.ic_params,
                    contentDescription = stringResource(R.string.map_tracker_info_view_params_content_description),
                    visible = showInlineButtons,
                    enabled = showInlineButtons && canOpenParams,
                    onClick = onParams,
                    tooltip = stringResource(R.string.tooltip_tracking_params),
                )
                SmallIconActionButton(
                    iconRes = R.drawable.ic_send_point,
                    contentDescription = stringResource(R.string.home_manual_send_point_content_description),
                    visible = showInlineButtons,
                    enabled = showInlineButtons,
                    onClick = onManualPoint,
                    tooltip = stringResource(R.string.tooltip_manual_send_point),
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
    valueColor: Color? = null,
) {
    val resolvedValueColor = valueColor ?: MaterialTheme.colors.onSurface
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, GeoVaultColorTokens.MainBlue),
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
                color = geoVaultContentSecondaryColor(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                color = resolvedValueColor,
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
    tooltip: String,
) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .alpha(if (visible) 1f else 0f),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, GeoVaultColorTokens.MainBlue),
        elevation = 0.dp,
    ) {
        GeoVaultClickableWithTooltip(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            enabled = enabled,
            tooltip = tooltip,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = GeoVaultColorTokens.MainBlue,
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
            .background(GeoVaultColorTokens.ScrimStrong)
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
            color = GeoVaultColorTokens.ErrorSurfaceLight,
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
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun openLocationPermissionSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
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
        TrackingUiStatus.PAUSED_FOR_MOTION -> R.string.tracking_active
        TrackingUiStatus.TRACKING_ACTIVE -> R.string.tracking_active
    }
}

private fun homeStatusColor(status: TrackingUiStatus, isPreparingToTrack: Boolean): androidx.compose.ui.graphics.Color {
    if (isPreparingToTrack) return GeoVaultColorTokens.MainBlue
    return when (status) {
        TrackingUiStatus.NOT_TRACKING -> GeoVaultColorTokens.MainBlue
        TrackingUiStatus.WAITING_FOR_GPS -> GeoVaultColorTokens.Error
        TrackingUiStatus.LOCKING -> GeoVaultColorTokens.MainYellow
        TrackingUiStatus.PAUSED_FOR_MOTION -> GeoVaultColorTokens.MainYellow
        TrackingUiStatus.TRACKING_ACTIVE -> GeoVaultColorTokens.MainYellow
    }
}

private fun formatDurationMs(isTracking: Boolean, sessionStartTimeMs: Long, nowMs: Long): String {
    if (!isTracking || sessionStartTimeMs <= 0L) return "\u2014"
    val totalSec = ((nowMs - sessionStartTimeMs) / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatHomeLastAgo(state: HomeUiState, nowMs: Long): String {
    if (!state.isTracking) return "\u2014"
    return HomeElapsedTimeFormat.format(state.lastPointSentAtMs.takeIf { it > 0L }, nowMs)
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

private fun homeTrackerParamsRouteArgsOrNull(state: HomeUiState): TrackerParamsRouteArgs? {
    val trackerId = state.selectedTrackerId.trim()
    if (trackerId.isBlank()) return null
    val lat = state.lastTrackedLatitude
    val lon = state.lastTrackedLongitude
    val trackerName = state.selectedTrackerDisplayName.ifBlank { state.selectedTrackerId }
    if (trackerName.isBlank()) return null
    val lastUpdateMs = when {
        state.lastTrackedTimestampMs > 0L -> state.lastTrackedTimestampMs
        state.lastPointSentAtMs > 0L -> state.lastPointSentAtMs
        else -> null
    }
    return TrackerParamsRouteArgs(
        trackerId = trackerId,
        seed = TrackerParamsSeed(
            displayName = trackerName,
            lastUpdateMs = lastUpdateMs,
            latitude = lat,
            longitude = lon,
            initialParams = null,
            isOwner = true,
        ),
    )
}
