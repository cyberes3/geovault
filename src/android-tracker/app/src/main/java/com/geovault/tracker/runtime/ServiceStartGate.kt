package com.geovault.tracker.runtime

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.geovault.tracker.TrackingService
import kotlin.math.min

data class StartGateDecision(
    val allowed: Boolean,
    val retryInMs: Long = 0L,
    val reason: String
)

class ServiceStartGate(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun dispatchStart(trigger: RuntimeTrigger, reason: String): StartGateDecision {
        val now = SystemClock.elapsedRealtime()
        val blockedUntil = prefs.getLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L)
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT_ELAPSED_MS, 0L)
        Log.i(
            TAG,
            "dispatchStart trigger=$trigger reason=$reason now=$now blockedUntil=$blockedUntil lastAttempt=$lastAttempt"
        )
        if (blockedUntil > now) {
            Log.w(TAG, "dispatchStart blocked by backoff retryInMs=${blockedUntil - now}")
            return StartGateDecision(
                allowed = false,
                retryInMs = blockedUntil - now,
                reason = "blocked_backoff"
            )
        }
        if (lastAttempt > 0L && now - lastAttempt < MIN_ATTEMPT_GAP_MS) {
            Log.w(TAG, "dispatchStart blocked by min gap retryInMs=${MIN_ATTEMPT_GAP_MS - (now - lastAttempt)}")
            return StartGateDecision(
                allowed = false,
                retryInMs = MIN_ATTEMPT_GAP_MS - (now - lastAttempt),
                reason = "min_gap"
            )
        }
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_ELAPSED_MS, now)
            .putString(KEY_LAST_TRIGGER, trigger.name)
            .apply()

        val intent = Intent(appContext, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            setPackage(appContext.packageName)
        }
        return try {
            appContext.startForegroundService(intent)
            prefs.edit().putInt(KEY_FAILURE_COUNT, 0).putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L).apply()
            Log.i(TAG, "dispatchStart success trigger=$trigger reason=$reason")
            StartGateDecision(allowed = true, reason = "start_dispatched:$reason")
        } catch (error: Exception) {
            val failureCount = (prefs.getInt(KEY_FAILURE_COUNT, 0) + 1).coerceAtMost(MAX_RETRY_ATTEMPTS)
            val retryDelay = computeRetryDelay(failureCount)
            val blocked = if (retryDelay > 0L) SystemClock.elapsedRealtime() + retryDelay else 0L
            prefs.edit()
                .putInt(KEY_FAILURE_COUNT, failureCount)
                .putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, blocked)
                .apply()
            if (retryDelay > 0L) {
                scheduleRetry(retryDelay)
            }
            val suffix = if (error is ForegroundServiceStartNotAllowedException) "fgs_denied" else error::class.java.simpleName
            Log.e(
                TAG,
                "dispatchStart failed trigger=$trigger reason=$reason failureCount=$failureCount retryDelay=$retryDelay blockedUntil=$blocked suffix=$suffix",
                error
            )
            StartGateDecision(
                allowed = false,
                retryInMs = retryDelay,
                reason = "start_failed_$suffix"
            )
        }
    }

    private fun computeRetryDelay(failureCount: Int): Long {
        if (failureCount <= 0 || failureCount >= MAX_RETRY_ATTEMPTS) return 0L
        return min(BASE_RETRY_BACKOFF_MS * (1L shl (failureCount - 1)), MAX_RETRY_BACKOFF_MS)
    }

    private fun scheduleRetry(retryInMs: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val retryIntent = Intent(appContext, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            setPackage(appContext.packageName)
        }
        val pendingIntent = PendingIntent.getService(
            appContext,
            RETRY_REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + retryInMs,
            pendingIntent
        )
        Log.i(TAG, "scheduleRetry retryInMs=$retryInMs")
    }

    companion object {
        private const val TAG = "TrackingStartGateV2"
        private const val PREFS_NAME = "tracking_start_gate_v2"
        private const val KEY_LAST_ATTEMPT_ELAPSED_MS = "last_attempt_elapsed_ms"
        private const val KEY_BLOCKED_UNTIL_ELAPSED_MS = "blocked_until_elapsed_ms"
        private const val KEY_FAILURE_COUNT = "failure_count"
        private const val KEY_LAST_TRIGGER = "last_trigger"
        private const val MIN_ATTEMPT_GAP_MS = 1500L
        private const val BASE_RETRY_BACKOFF_MS = 2000L
        private const val MAX_RETRY_BACKOFF_MS = 30_000L
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val RETRY_REQUEST_CODE = 22001
    }
}
