package com.geovault.tracker.streaming

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.LiveTrackStreamingService
import kotlin.math.min

/**
 * FGS-START-RESTRICTION PARITY: mirrors [com.geovault.tracker.runtime.ServiceStartGate]'s
 * backoff-and-retry-via-`AlarmManager` design so a live-track start request that Android
 * outright refuses (both `startService` and `startForegroundService` denied — e.g. a reconcile
 * tick landing while the app is fully backgrounded) is retried automatically instead of being
 * silently dropped, the way [MapStreamingServiceHelper.startStreaming] previously did. An
 * `AlarmManager`-delivered `PendingIntent` gets a brief background-execution exemption, which is
 * what lets the retry actually succeed once the OS's restriction window has passed.
 */
internal object StreamingStartRetryScheduler {
    private const val TAG = "StreamingStartRetryScheduler"
    private const val PREFS_NAME = "live_track_streaming_start_gate"
    private const val KEY_FAILURE_COUNT = "failure_count"
    private const val BASE_RETRY_BACKOFF_MS = 2_000L
    private const val MAX_RETRY_BACKOFF_MS = 30_000L
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val RETRY_REQUEST_CODE = 42_001

    fun scheduleRetry(context: Context, trackerIds: Set<String>, trackerName: String?) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val failureCount = (prefs.getInt(KEY_FAILURE_COUNT, 0) + 1).coerceAtMost(MAX_RETRY_ATTEMPTS)
        prefs.edit().putInt(KEY_FAILURE_COUNT, failureCount).apply()
        if (failureCount >= MAX_RETRY_ATTEMPTS) {
            GeoVaultCaptureLog.e(TAG, "Exhausted retry attempts for live streaming start; giving up")
            return
        }
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            GeoVaultCaptureLog.w(TAG, "AlarmManager unavailable; cannot schedule streaming start retry")
            return
        }
        val retryDelayMs = min(BASE_RETRY_BACKOFF_MS * (1L shl (failureCount - 1)), MAX_RETRY_BACKOFF_MS)
        val retryIntent = Intent(appContext, LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_START
            putStringArrayListExtra(LiveTrackStreamingService.EXTRA_TRACKER_IDS, ArrayList(trackerIds))
            putExtra(LiveTrackStreamingService.EXTRA_TRACKER_NAME, trackerName)
        }
        val pendingIntent = PendingIntent.getService(
            appContext,
            RETRY_REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + retryDelayMs,
                pendingIntent,
            )
        }.onFailure { error ->
            GeoVaultCaptureLog.e(TAG, "Failed to schedule streaming start retry", error)
        }
        GeoVaultCaptureLog.w(TAG, "Scheduled live streaming start retry in ${retryDelayMs}ms attempt=$failureCount")
    }

    fun resetFailureCount(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FAILURE_COUNT, 0)
            .apply()
    }
}
