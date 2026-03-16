package com.geovault.tracker

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

object SelectedTrackerManager {
    private const val RESTART_DELAY_MS = 400L

    fun setSelectedTracker(
        context: Context,
        trackerId: String,
        trackerName: String?,
        restartTrackingIfRunning: Boolean = true
    ) {
        SelectedTrackerPrefs.setSelectedTracker(context, trackerId, trackerName)
        if (restartTrackingIfRunning) {
            restartTrackingIfRunning(context)
        }
    }

    fun clearSelectedTracker(context: Context) {
        SelectedTrackerPrefs.clearSelectedTracker(context)
    }

    fun clearSelectedTrackerAndInvalidateCaches(
        context: Context,
        clearTrackersListCache: Boolean = false
    ) {
        clearSelectedTracker(context)
        TrackerRepository.clearSelectedTrackerCaches()
        if (clearTrackersListCache) {
            TrackerRepository.clearListCaches()
        }
    }

    fun restartTrackingIfRunning(context: Context, delayMs: Long = RESTART_DELAY_MS) {
        if (!TrackingService.isRunning) return
        context.startService(Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        })
        Handler(Looper.getMainLooper()).postDelayed({
            context.startForegroundService(Intent(context, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START
            })
        }, delayMs)
    }
}
