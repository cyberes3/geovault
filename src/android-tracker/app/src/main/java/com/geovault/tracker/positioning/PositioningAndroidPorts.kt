package com.geovault.tracker.positioning

import android.app.Service
import android.content.Context
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceConstants

internal class PositioningAndroidPorts(
    val service: TrackingService,
) {
    val context: Context get() = service.applicationContext

    fun selectedTrackerId(): String = SelectedTrackerPrefs.selectedTrackerId(service)

    fun notificationId(): Int = TrackingServiceConstants.NOTIFICATION_ID
}
