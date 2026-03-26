package com.geovault.tracker.runtime

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingService
import com.geovault.tracker.location.TrackingPermissionGate

data class HealthEvaluation(
    val isHealthy: Boolean,
    val shouldRecover: Boolean,
    val reason: String
)

class TrackingHealthSupervisor(private val context: Context) {
    private val appContext = context.applicationContext

    fun evaluate(state: RuntimeState): HealthEvaluation {
        Log.d(
            TAG,
            "evaluate desired=${state.shouldBeRunning} lifecycle=${state.lifecycleState} lastHeartbeatAtMs=${state.lastHeartbeatAtMs}"
        )
        if (!state.shouldBeRunning) {
            Log.d(TAG, "evaluate result=healthy reason=not_desired")
            return HealthEvaluation(isHealthy = true, shouldRecover = false, reason = "not_desired")
        }
        val now = System.currentTimeMillis()
        val stale = state.lastHeartbeatAtMs <= 0L || (now - state.lastHeartbeatAtMs) > HEARTBEAT_STALE_MS
        if (!stale) {
            Log.d(TAG, "evaluate result=healthy reason=heartbeat_fresh ageMs=${now - state.lastHeartbeatAtMs}")
            return HealthEvaluation(isHealthy = true, shouldRecover = false, reason = "heartbeat_fresh")
        }
        if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(appContext)) {
            Log.w(TAG, "evaluate result=unhealthy reason=permissions_missing")
            return HealthEvaluation(isHealthy = false, shouldRecover = false, reason = "permissions_missing")
        }
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(appContext)
        if (!TrackingService.hasValidSelectedTrackerId(trackerId)) {
            Log.w(TAG, "evaluate result=unhealthy reason=invalid_tracker")
            return HealthEvaluation(isHealthy = false, shouldRecover = false, reason = "invalid_tracker")
        }
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "evaluate result=unhealthy reason=gps_disabled")
            return HealthEvaluation(isHealthy = false, shouldRecover = false, reason = "gps_disabled")
        }
        Log.w(TAG, "evaluate result=recover reason=heartbeat_stale ageMs=${now - state.lastHeartbeatAtMs}")
        return HealthEvaluation(isHealthy = false, shouldRecover = true, reason = "heartbeat_stale")
    }

    companion object {
        private const val TAG = "TrackingHealthV2"
        const val HEARTBEAT_STALE_MS = 30_000L
    }
}
