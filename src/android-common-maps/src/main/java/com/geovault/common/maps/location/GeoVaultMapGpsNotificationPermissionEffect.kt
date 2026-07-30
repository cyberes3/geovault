package com.geovault.common.maps.location

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Requests [Manifest.permission.POST_NOTIFICATIONS] when continuous map GPS becomes active so the
 * location foreground-service notification can be shown.
 *
 * Denial does not block GPS streaming; it only hides the ongoing notification.
 */
@Composable
fun GeoVaultMapGpsNotificationPermissionEffect(requestWhen: Boolean) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* GPS continues whether granted or denied. */ }
    LaunchedEffect(requestWhen) {
        if (requestWhen && !context.geoVaultMapHasPostNotifications()) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
