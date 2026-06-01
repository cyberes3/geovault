package com.geovault.tracker.positioning

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceConstants

internal class PositioningAndroidPorts(
    val service: TrackingService,
) {
    val context: Context get() = service.applicationContext

    fun selectedTrackerId(): String = SelectedTrackerPrefs.selectedTrackerId(service)

    fun selectedTrackerName(): String = SelectedTrackerPrefs.selectedTrackerName(service)

    fun notificationId(): Int = TrackingServiceConstants.NOTIFICATION_ID

    fun startForeground(notification: Notification) {
        service.startForeground(
            notificationId(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    fun triggerLightHaptic() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val vibratorManager = service.getSystemService(VibratorManager::class.java) ?: return
        val vibrator = vibratorManager.defaultVibrator
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
