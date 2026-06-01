package com.geovault.tracker.runtime

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceIntents
import com.geovault.tracker.tracking.TrackingServiceConstants
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
        val persistedBlockedUntil = prefs.getLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L)
        val persistedLastAttempt = prefs.getLong(KEY_LAST_ATTEMPT_ELAPSED_MS, 0L)
        val (blockedUntil, lastAttempt) = sanitizePersistedElapsedState(
            now = now,
            blockedUntil = persistedBlockedUntil,
            lastAttempt = persistedLastAttempt
        )
        GeoVaultCaptureLog.i(
            TAG,
            "dispatchStart trigger=$trigger reason=$reason now=$now blockedUntil=$blockedUntil lastAttempt=$lastAttempt"
        )
        if (blockedUntil > now) {
            val retryInMs = blockedUntil - now
            if (retryInMs > 0L) {
                scheduleRetry(retryInMs)
            }
            GeoVaultCaptureLog.w(TAG, "dispatchStart blocked by backoff retryInMs=${blockedUntil - now}")
            return StartGateDecision(
                allowed = false,
                retryInMs = retryInMs,
                reason = "blocked_backoff"
            )
        }
        if (lastAttempt > 0L && now - lastAttempt < MIN_ATTEMPT_GAP_MS) {
            val retryInMs = MIN_ATTEMPT_GAP_MS - (now - lastAttempt)
            if (retryInMs > 0L) {
                scheduleRetry(retryInMs)
            }
            GeoVaultCaptureLog.w(TAG, "dispatchStart blocked by min gap retryInMs=$retryInMs")
            return StartGateDecision(
                allowed = false,
                retryInMs = retryInMs,
                reason = "min_gap"
            )
        }
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_ELAPSED_MS, now)
            .putString(KEY_LAST_TRIGGER, trigger.name)
            .apply()

        val intent = Intent(appContext, TrackingService::class.java).apply {
            action = TrackingServiceIntents.ACTION_START
            setPackage(appContext.packageName)
        }
        return try {
            appContext.startForegroundService(intent)
            prefs.edit().putInt(KEY_FAILURE_COUNT, 0).putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L).apply()
            GeoVaultCaptureLog.i(TAG, "dispatchStart success trigger=$trigger reason=$reason")
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
            GeoVaultCaptureLog.e(
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

    private fun sanitizePersistedElapsedState(
        now: Long,
        blockedUntil: Long,
        lastAttempt: Long
    ): Pair<Long, Long> {
        var sanitizedBlockedUntil = blockedUntil
        var sanitizedLastAttempt = lastAttempt
        var changed = false

        if (sanitizedLastAttempt < 0L || sanitizedLastAttempt > now) {
            sanitizedLastAttempt = 0L
            changed = true
        }
        if (
            sanitizedBlockedUntil < 0L ||
            sanitizedBlockedUntil > now + MAX_RETRY_BACKOFF_MS
        ) {
            sanitizedBlockedUntil = 0L
            changed = true
        }

        if (changed) {
            prefs.edit()
                .putLong(KEY_LAST_ATTEMPT_ELAPSED_MS, sanitizedLastAttempt)
                .putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, sanitizedBlockedUntil)
                .putInt(KEY_FAILURE_COUNT, 0)
                .apply()
            GeoVaultCaptureLog.w(
                TAG,
                "reset invalid elapsed state now=$now blockedUntil=$blockedUntil lastAttempt=$lastAttempt"
            )
        }
        return sanitizedBlockedUntil to sanitizedLastAttempt
    }

    private fun computeRetryDelay(failureCount: Int): Long {
        if (failureCount <= 0 || failureCount >= MAX_RETRY_ATTEMPTS) return 0L
        return min(BASE_RETRY_BACKOFF_MS * (1L shl (failureCount - 1)), MAX_RETRY_BACKOFF_MS)
    }

    private fun scheduleRetry(retryInMs: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val retryIntent = Intent(appContext, TrackingService::class.java).apply {
            action = TrackingServiceIntents.ACTION_START
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
        GeoVaultCaptureLog.i(TAG, "scheduleRetry retryInMs=$retryInMs")
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
