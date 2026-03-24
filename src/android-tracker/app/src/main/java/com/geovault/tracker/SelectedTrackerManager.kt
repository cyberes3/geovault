package com.geovault.tracker

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.startup.TrackingServiceLaunchGate

object SelectedTrackerManager {
    private const val TAG = "SelectedTrackerManager"
    private const val RESTART_DELAY_MS = 400L
    private val restartHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    fun setSelectedTracker(
        context: Context,
        trackerId: String,
        trackerName: String?,
        restartTrackingIfRunning: Boolean = true
    ) {
        val persisted = SelectedTrackerPrefs.setSelectedTracker(context, trackerId, trackerName)
        if (!persisted) {
            Log.w(TAG, "Failed to persist selected tracker id=$trackerId before tracking restart")
        }
        syncRuntimeSelectedTracker(context)
        if (restartTrackingIfRunning) {
            restartTrackingIfRunning(context)
        }
    }

    fun clearSelectedTracker(context: Context) {
        cancelPendingRestart()
        SelectedTrackerPrefs.clearSelectedTracker(context)
        syncRuntimeSelectedTracker(context)
        stopTrackingIfRunning(context)
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
            TrackingServiceLaunchGate.dispatchStart(
                context = appContext,
                trigger = "selected_tracker_restart"
            )
            pendingRestart = null
        }
        pendingRestart = restartRunnable
        restartHandler.postDelayed(restartRunnable, delayMs)
    }

    fun syncRuntimeSelectedTracker(context: Context) {
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(context)
        val selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(context)
        TrackingRuntimeStateStore.update {
            it.copy(
                selectedTrackerId = selectedTrackerId,
                selectedTrackerName = selectedTrackerName
            )
        }
    }

    private fun stopTrackingIfRunning(context: Context) {
        if (!TrackingRuntimeStateStore.state.value.isRunning) return
        val appContext = context.applicationContext
        appContext.startService(Intent(appContext, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        })
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let { restartHandler.removeCallbacks(it) }
        pendingRestart = null
    }
}
