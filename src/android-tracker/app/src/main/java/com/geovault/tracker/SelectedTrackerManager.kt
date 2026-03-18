package com.geovault.tracker

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.geovault.tracker.services.TrackingRuntimeStateStore

object SelectedTrackerManager {
    private const val RESTART_DELAY_MS = 400L
    private val restartHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

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
        cancelPendingRestart()
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
        if (!TrackingRuntimeStateStore.state.value.isRunning) {
            cancelPendingRestart()
            return
        }
        val appContext = context.applicationContext
        cancelPendingRestart()
        appContext.startService(Intent(appContext, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        })
        val restartRunnable = Runnable {
            appContext.startForegroundService(Intent(appContext, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START
            })
            pendingRestart = null
        }
        pendingRestart = restartRunnable
        restartHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let { restartHandler.removeCallbacks(it) }
        pendingRestart = null
    }
}
