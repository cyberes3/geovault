package com.geovault.tracker.aar

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.sensor.ActivityHint
import com.geovault.tracker.sensor.ActivityHintSource
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Production [ActivityHintSource] that registers GMS activity-transition updates and exposes
 * the result through a pull-based interface.
 *
 * A `@Volatile` [companion object] instance pointer is used so that the stateless
 * [ActivityTransitionUpdateReceiver] can deliver events without service routing.
 *
 * No imports from the positioning or policy packages are allowed in this class; the
 * only outward channel is [ActivityHintSource.currentHint].
 */
internal class ActivityRecognitionHintBridge(
    private val context: Context,
) : ActivityHintSource {

    private val store = ActivityRecognitionHintStore()
    private var pendingIntent: PendingIntent? = null
    private var recorder: ActivityRecognitionHintRecorder? = null

    @Volatile
    private var activeTrackingGeneration: Int = -1

    override fun currentHint(nowMs: Long): ActivityHint? = store.currentHint(nowMs)

    override fun start(context: Context, trackId: String, trackingGeneration: Int) {
        if (!hasActivityRecognitionPermission(context)) {
            GeoVaultCaptureLog.w(TAG, "aar_permission_missing")
            return
        }
        stop()
        activeTrackingGeneration = trackingGeneration
        recorder = ActivityRecognitionHintRecorder(trackId, trackingGeneration)
        instance = this
        registerTransitionUpdates(context)
        GeoVaultCaptureLog.d(TAG, "aar_bridge_start trackId=$trackId generation=$trackingGeneration")
    }

    override fun stop() {
        val pi = pendingIntent
        if (pi != null) {
            runCatching {
                ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pi)
                    .addOnSuccessListener { GeoVaultCaptureLog.i(TAG, "AAR GMS unregistration succeeded") }
                    .addOnFailureListener { e -> GeoVaultCaptureLog.w(TAG, "AAR GMS unregistration failed: ${e.message}") }
            }.onFailure { e ->
                GeoVaultCaptureLog.w(TAG, "AAR GMS removeActivityTransitionUpdates exception: ${e.message}")
            }
            pi.cancel()
        }
        pendingIntent = null
        recorder = null
        store.clear()
        activeTrackingGeneration = -1
        if (instance === this) instance = null
        GeoVaultCaptureLog.d(TAG, "aar_bridge_stop")
    }

    internal fun onTransition(event: ActivityTransitionEvent) {
        val generation = activeTrackingGeneration
        if (generation < 0) {
            GeoVaultCaptureLog.w(TAG, "aar_transition_stale_dropped received_gen=unknown active_gen=none")
            return
        }
        val nowMs = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val activityLabel = activityLabel(event.activityType)
        val transitionLabel = transitionLabel(event.transitionType)

        val hintActive = ActivityRecognitionHintPolicy.hintActive(event.activityType, event.transitionType)

        if (hintActive) {
            store.setHint(nowMs + ActivityRecognitionHintPolicy.HINT_DURATION_MS)
        } else if (ActivityRecognitionHintPolicy.isClearingTransition(event.activityType, event.transitionType)) {
            store.clear()
        }
        GeoVaultCaptureLog.d(
            TAG,
            "aar_transition activity=$activityLabel type=$transitionLabel hintActive=$hintActive generation=$generation",
        )

        recorder?.record(
            wallMs = nowMs,
            elapsedRealtimeNanos = elapsedNanos,
            eventTimeMs = event.elapsedRealTimeNanos / 1_000_000L,
            activityLabel = activityLabel,
            transitionLabel = transitionLabel,
            hintActive = hintActive,
        )
    }

    private fun registerTransitionUpdates(context: Context) {
        val intent = Intent(context, ActivityTransitionUpdateReceiver::class.java).apply {
            action = ACTION_TRANSITION_UPDATE
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        pendingIntent = pi

        val request = ActivityTransitionRequest(buildTransitions())
        runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(request, pi)
                .addOnSuccessListener {
                    GeoVaultCaptureLog.d(TAG, "aar_registered transitions=${buildTransitions().size}")
                }
                .addOnFailureListener { e ->
                    GeoVaultCaptureLog.e(TAG, "aar_registration_failed reason=${e.message}")
                    if (instance === this@ActivityRecognitionHintBridge) instance = null
                    activeTrackingGeneration = -1
                }
        }.onFailure { e ->
            GeoVaultCaptureLog.e(TAG, "aar_registration_failed reason=${e.message}")
            if (instance === this) instance = null
            activeTrackingGeneration = -1
        }
    }

    private fun buildTransitions(): List<ActivityTransition> {
        val monitoredActivities = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.RUNNING,
            DetectedActivity.WALKING,
            DetectedActivity.ON_FOOT,
            DetectedActivity.STILL,
        )
        val transitionTypes = listOf(
            ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            ActivityTransition.ACTIVITY_TRANSITION_EXIT,
        )
        return monitoredActivities.flatMap { activityType ->
            transitionTypes.map { transitionType ->
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(transitionType)
                    .build()
            }
        }
    }

    companion object {
        @Volatile
        var instance: ActivityRecognitionHintBridge? = null
            private set

        private const val TAG = "GeoVaultAAR"
        private const val ACTION_TRANSITION_UPDATE = "com.geovault.tracker.AAR_TRANSITION_UPDATE"
        private const val REQUEST_CODE = 0x4141_5200

        private fun hasActivityRecognitionPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

        internal fun activityLabel(type: Int): String = when (type) {
            DetectedActivity.IN_VEHICLE -> "in_vehicle"
            DetectedActivity.ON_BICYCLE -> "on_bicycle"
            DetectedActivity.ON_FOOT -> "on_foot"
            DetectedActivity.RUNNING -> "running"
            DetectedActivity.WALKING -> "walking"
            DetectedActivity.STILL -> "still"
            DetectedActivity.TILTING -> "tilting"
            DetectedActivity.UNKNOWN -> "unknown"
            else -> "type_$type"
        }

        internal fun transitionLabel(type: Int): String = when (type) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "enter"
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "exit"
            else -> "type_$type"
        }
    }
}
