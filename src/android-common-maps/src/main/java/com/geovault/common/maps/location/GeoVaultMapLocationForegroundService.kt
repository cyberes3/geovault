package com.geovault.common.maps.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.geovault.common.maps.R
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Thin foreground-service shell that elevates the process for continuous map GPS.
 *
 * Business logic (fused session ownership, listeners, refcounting) lives in
 * [GeoVaultMapGpsLocationEngine]. This service only calls [startForeground] with type location.
 */
class GeoVaultMapLocationForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gv_common_map_gps_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.gv_common_map_gps_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.gv_common_map_gps_notification_title))
            .setContentText(getString(R.string.gv_common_map_gps_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setColor(GeoVaultColorTokens.MainBlue.toArgb())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    internal companion object {
        const val CHANNEL_ID = "gv_common_map_gps_location"
        const val NOTIFICATION_ID = 0x67766D47 // "gvMG"
    }
}
