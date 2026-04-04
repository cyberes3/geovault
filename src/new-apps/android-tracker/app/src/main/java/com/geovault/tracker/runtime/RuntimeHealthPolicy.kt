package com.geovault.tracker.runtime

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingService
import com.geovault.tracker.location.TrackingPermissionGate

data class RuntimeHealthEvaluation(
    val isHealthy: Boolean,
    val shouldRecover: Boolean,
    val reason: String
)

class RuntimeHealthPolicy(private val context: Context) {
    private val appContext = context.applicationContext

    fun reconcileState(current: RuntimeState, isServiceRunning: Boolean, reason: String): RuntimeState {
        val now = System.currentTimeMillis()
        if (isServiceRunning) {
            if (current.lifecycleState == RuntimeLifecycleState.IDLE || !current.shouldBeRunning) {
                Log.i(TAG, "reconcile promote_to_active reason=$reason")
                return current.copy(
                    lifecycleState = RuntimeLifecycleState.ACTIVE,
                    shouldBeRunning = true,
                    lastIntentionalStop = false,
                    lastTransitionAtMs = now
                )
            }
            return current
        }

        val serviceExpected = current.lifecycleState == RuntimeLifecycleState.ACTIVE ||
            current.lifecycleState == RuntimeLifecycleState.STARTING ||
            current.lifecycleState == RuntimeLifecycleState.RECOVERING
        if (!serviceExpected) return current

        Log.w(TAG, "reconcile reset_stale_active_state reason=$reason lifecycle=${current.lifecycleState}")
        return current.copy(
            lifecycleState = RuntimeLifecycleState.IDLE,
            shouldBeRunning = false,
            lastIntentionalStop = false,
            lastFailure = RuntimeFailure(
                clazz = RuntimeFailureClass.TRANSIENT,
                reason = "service_not_running_state_reconciled:$reason"
            ),
            lastTransitionAtMs = now
        )
    }

    fun evaluateRecoveryHealth(state: RuntimeState): RuntimeHealthEvaluation {
        if (!state.shouldBeRunning) {
            return RuntimeHealthEvaluation(
                isHealthy = true,
                shouldRecover = false,
                reason = "not_desired"
            )
        }
        val now = System.currentTimeMillis()
        val stale = state.lastHeartbeatAtMs <= 0L || (now - state.lastHeartbeatAtMs) > HEARTBEAT_STALE_MS
        if (!stale) {
            return RuntimeHealthEvaluation(
                isHealthy = true,
                shouldRecover = false,
                reason = "heartbeat_fresh"
            )
        }
        if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(appContext)) {
            return RuntimeHealthEvaluation(
                isHealthy = false,
                shouldRecover = false,
                reason = "permissions_missing"
            )
        }
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(appContext)
        if (!TrackingService.hasValidSelectedTrackerId(trackerId)) {
            return RuntimeHealthEvaluation(
                isHealthy = false,
                shouldRecover = false,
                reason = "invalid_tracker"
            )
        }
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return RuntimeHealthEvaluation(
                isHealthy = false,
                shouldRecover = false,
                reason = "gps_disabled"
            )
        }
        return RuntimeHealthEvaluation(
            isHealthy = false,
            shouldRecover = true,
            reason = "heartbeat_stale"
        )
    }

    companion object {
        private const val TAG = "RuntimeHealthPolicy"
        const val HEARTBEAT_STALE_MS = 30_000L
    }
}
