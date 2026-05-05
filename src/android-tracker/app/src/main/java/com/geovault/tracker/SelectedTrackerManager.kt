package com.geovault.tracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.services.TrackingRuntimeStateStore

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
            Log.w(TAG, "Failed to persist selected tracker id=$trackerId before restart")
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

    fun clearSelectedTrackerAndInvalidateCaches(context: Context) {
        clearSelectedTracker(context)
        val application = context.applicationContext as? android.app.Application
        if (application == null) {
            Log.w(TAG, "Unable to clear caches: application context unavailable")
            return
        }
        TrackerAppServices.from(application).trackerManagementRepository().clearSelectedTrackerCaches()
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

    fun updateSelectedTrackerNameIfSelected(context: Context, trackerId: String, trackerName: String?) {
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(context)
        if (selectedTrackerId != trackerId) return
        SelectedTrackerPrefs.updateSelectedTrackerName(context, trackerName)
        syncRuntimeSelectedTracker(context)
    }

    fun restartTrackingIfRunning(context: Context, delayMs: Long = RESTART_DELAY_MS) {
        if (!TrackingRuntimeStateStore.state.value.isRunning) {
            cancelPendingRestart()
            return
        }
        val appContext = context.applicationContext
        cancelPendingRestart()
        val stopGenerationAtRestart = TrackingCommandFacade.stopGeneration()
        TrackingCommandFacade.requestStop(appContext, reason = "selected_tracker_restart_stop")
        val runnable = Runnable {
            if (TrackingCommandFacade.stopGeneration() != stopGenerationAtRestart) {
                pendingRestart = null
                return@Runnable
            }
            TrackingCommandFacade.requestStart(
                context = appContext,
                trigger = RuntimeTrigger.EXPLICIT_START,
                reason = "selected_tracker_restart_start"
            )
            pendingRestart = null
        }
        pendingRestart = runnable
        restartHandler.postDelayed(runnable, delayMs)
    }

    private fun stopTrackingIfRunning(context: Context) {
        if (!TrackingRuntimeStateStore.state.value.isRunning) return
        TrackingCommandFacade.requestStop(context.applicationContext, reason = "selected_tracker_cleared")
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let { restartHandler.removeCallbacks(it) }
        pendingRestart = null
    }
}
