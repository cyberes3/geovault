package com.geovault.tracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.services.TrackingRuntimeStateStore

object SelectedTrackerManager {
    private const val TAG = "SelectedTrackerManager"
    private const val RESTART_DELAY_MS = 400L
    private const val RESTART_START_RETRY_MS = 400L
    private const val MAX_RESTART_START_ATTEMPTS = 10
    private val restartHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    fun setSelectedTracker(
        context: Context,
        trackerId: String,
        trackerName: String?,
        restartTrackingIfRunning: Boolean = true
    ) {
        val normalizedTrackerId = trackerId.trim()
        val previouslySelectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(context).trim()
        val selectionUnchanged = normalizedTrackerId.isNotEmpty() && normalizedTrackerId == previouslySelectedTrackerId
        val persisted = SelectedTrackerPrefs.setSelectedTracker(context, normalizedTrackerId, trackerName)
        if (!persisted) {
            GeoVaultCaptureLog.w(TAG, "Failed to persist selected tracker id=$normalizedTrackerId before restart")
        }
        syncRuntimeSelectedTracker(context)
        if (selectionUnchanged) {
            GeoVaultCaptureLog.i(
                TAG,
                "selected_tracker_action action=already_selected_no_restart trackerId=$normalizedTrackerId " +
                    "restartRequested=$restartTrackingIfRunning"
            )
            return
        }
        if (restartTrackingIfRunning) {
            GeoVaultCaptureLog.i(
                TAG,
                "selected_tracker_action action=changed_restart previousTrackerId=$previouslySelectedTrackerId " +
                    "trackerId=$normalizedTrackerId"
            )
            restartTrackingIfRunning(context)
        } else {
            GeoVaultCaptureLog.i(
                TAG,
                "selected_tracker_action action=changed_no_restart previousTrackerId=$previouslySelectedTrackerId " +
                    "trackerId=$normalizedTrackerId"
            )
        }
    }

    fun clearSelectedTracker(context: Context) {
        val previousTrackerId = SelectedTrackerPrefs.selectedTrackerId(context).trim()
        cancelPendingRestart()
        SelectedTrackerPrefs.clearSelectedTracker(context)
        syncRuntimeSelectedTracker(context)
        GeoVaultCaptureLog.i(TAG, "selected_tracker_action action=cleared_stop previousTrackerId=$previousTrackerId")
        stopTrackingIfRunning(context)
    }

    fun clearSelectedTrackerAndInvalidateCaches(context: Context) {
        clearSelectedTracker(context)
        val application = context.applicationContext as? android.app.Application
        if (application == null) {
            GeoVaultCaptureLog.w(TAG, "Unable to clear caches: application context unavailable")
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
            GeoVaultCaptureLog.i(TAG, "selected_tracker_action action=restart_skipped_not_running")
            return
        }
        val appContext = context.applicationContext
        cancelPendingRestart()
        val stopGenerationAtRestart = TrackingCommandFacade.stopGeneration()
        TrackingCommandFacade.requestStop(appContext, reason = "selected_tracker_restart_stop")
        scheduleRestartStart(
            context = appContext,
            stopGenerationAtRestart = stopGenerationAtRestart,
            delayMs = delayMs,
            attempt = 1,
        )
    }

    private fun stopTrackingIfRunning(context: Context) {
        if (!TrackingRuntimeStateStore.state.value.isRunning) return
        TrackingCommandFacade.requestStop(context.applicationContext, reason = "selected_tracker_cleared")
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let { restartHandler.removeCallbacks(it) }
        pendingRestart = null
    }

    private fun scheduleRestartStart(
        context: Context,
        stopGenerationAtRestart: Long,
        delayMs: Long,
        attempt: Int,
    ) {
        val runnable = Runnable {
            if (TrackingCommandFacade.stopGeneration() != stopGenerationAtRestart) {
                GeoVaultCaptureLog.i(TAG, "selected_tracker_action action=restart_cancelled_generation_changed")
                pendingRestart = null
                return@Runnable
            }
            if (TrackingRuntimeStateStore.state.value.isRunning && attempt < MAX_RESTART_START_ATTEMPTS) {
                GeoVaultCaptureLog.i(
                    TAG,
                    "selected_tracker_action action=restart_start_waiting_for_stop attempt=$attempt"
                )
                scheduleRestartStart(
                    context = context,
                    stopGenerationAtRestart = stopGenerationAtRestart,
                    delayMs = RESTART_START_RETRY_MS,
                    attempt = attempt + 1,
                )
                return@Runnable
            }
            TrackingCommandFacade.requestStart(
                context = context,
                trigger = RuntimeTrigger.EXPLICIT_START,
                reason = "selected_tracker_restart_start"
            )
            pendingRestart = null
        }
        pendingRestart = runnable
        restartHandler.postDelayed(runnable, delayMs)
    }
}
