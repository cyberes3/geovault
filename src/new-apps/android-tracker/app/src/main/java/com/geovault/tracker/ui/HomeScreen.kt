package com.geovault.tracker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.tracker.R
import com.geovault.tracker.presentation.HomeViewModel

@Composable
fun HomeScreen(
    isAuthenticated: Boolean,
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
) {
    val homeViewModel: HomeViewModel = viewModel()
    val homeState by homeViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
        authenticatedMainContent = {
            val perms = homeState.permissions
            if (!perms.readyForTracking) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colors.surface,
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.home_permissions_section_title),
                            style = MaterialTheme.typography.subtitle1,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (!perms.hasForegroundLocation) {
                            OutlinedButton(
                                onClick = {
                                    foregroundLocationLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.home_permission_foreground))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (perms.hasForegroundLocation && !perms.hasBackgroundLocation) {
                            OutlinedButton(
                                onClick = {
                                    backgroundLocationLauncher.launch(
                                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.home_permission_background))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (!perms.hasPostNotifications) {
                            OutlinedButton(
                                onClick = {
                                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.home_permission_notifications))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        TextButton(
                            onClick = { openAppPermissionSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.home_open_system_app_settings))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = stringResource(R.string.home_status_section_title),
                style = MaterialTheme.typography.subtitle1,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.home_status_tracker_line,
                    if (homeState.selectedTrackerDisplayName.isBlank()) {
                        stringResource(R.string.home_tracker_none)
                    } else {
                        homeState.selectedTrackerDisplayName
                    },
                ),
                style = MaterialTheme.typography.body1,
            )
            Text(
                text = stringResource(
                    R.string.home_status_lifecycle_line,
                    homeState.lifecycleState.name,
                ),
                style = MaterialTheme.typography.body2,
            )
            Text(
                text = stringResource(
                    R.string.home_status_gps_line,
                    stringResource(
                        if (homeState.gpsProviderEnabled) {
                            R.string.home_status_gps_on
                        } else {
                            R.string.home_status_gps_off
                        },
                    ),
                ),
                style = MaterialTheme.typography.body2,
            )
            Text(
                text = stringResource(
                    R.string.tracking_notification_counts_line,
                    homeState.pointsSentThisSession,
                    homeState.queuedPointsVisible,
                ),
                style = MaterialTheme.typography.body2,
            )

            if (!infoMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colors.surface,
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = infoMessage,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onClearInfoMessage) {
                            Text(stringResource(R.string.home_dismiss_message))
                        }
                    }
                }
            }

            if (homeState.statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = homeState.statusMessage,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.error,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            val startEnabled = !homeState.isTracking &&
                perms.readyForTracking &&
                homeState.gpsProviderEnabled &&
                homeState.selectedTrackerId.isNotBlank()

            Button(
                onClick = onRequestStartTracking,
                enabled = startEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_start_tracking))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestStopTracking,
                enabled = homeState.isTracking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.stop_tracking))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestManualPoint,
                enabled = homeState.isTracking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_send_manual_point))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_open_app_settings))
            }
        },
    )
}

private fun openAppPermissionSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
