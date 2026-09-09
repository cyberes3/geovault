package com.geovault.common.service

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.geovault.common.logging.GeoVaultCaptureLog
import kotlin.math.min

/**
 * Starts a foreground service with backoff and an AlarmManager retry. [cancelRetry] must run
 * on every terminal path (stop, logout, successful apply, prune, permanent fail).
 */
class GeoVaultForegroundStartGate(
    context: Context,
    private val config: Config,
    private val startIntentFactory: () -> Intent,
    private val starter: ForegroundServiceStarter = DefaultForegroundServiceStarter,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    data class Config(
        val prefsName: String,
        val retryRequestCode: Int,
        val tag: String,
        val minAttemptGapMs: Long = DEFAULT_MIN_ATTEMPT_GAP_MS,
        val baseRetryBackoffMs: Long = DEFAULT_BASE_RETRY_BACKOFF_MS,
        val maxRetryBackoffMs: Long = DEFAULT_MAX_RETRY_BACKOFF_MS,
        val maxRetryAttempts: Int = DEFAULT_MAX_RETRY_ATTEMPTS,
    )

    data class Decision(
        val allowed: Boolean,
        val retryInMs: Long = 0L,
        val reason: String,
        val exhausted: Boolean = false,
    )

    fun interface ForegroundServiceStarter {
        fun start(context: Context, intent: Intent)
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(config.prefsName, Context.MODE_PRIVATE)

    fun dispatchStart(reason: String): Decision {
        val now = elapsedClock()
        val persistedBlockedUntil = prefs.getLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L)
        val persistedLastAttempt = prefs.getLong(KEY_LAST_ATTEMPT_ELAPSED_MS, 0L)
        val (blockedUntil, lastAttempt) = sanitizePersistedElapsedState(
            now = now,
            blockedUntil = persistedBlockedUntil,
            lastAttempt = persistedLastAttempt,
        )
        GeoVaultCaptureLog.i(
            config.tag,
            "dispatchStart reason=$reason now=$now blockedUntil=$blockedUntil lastAttempt=$lastAttempt",
        )
        if (blockedUntil > now) {
            val retryInMs = blockedUntil - now
            if (retryInMs > 0L) {
                scheduleRetry(retryInMs)
            }
            GeoVaultCaptureLog.w(config.tag, "dispatchStart blocked by backoff retryInMs=$retryInMs")
            return Decision(allowed = false, retryInMs = retryInMs, reason = "blocked_backoff")
        }
        if (lastAttempt > 0L && now - lastAttempt < config.minAttemptGapMs) {
            val retryInMs = config.minAttemptGapMs - (now - lastAttempt)
            if (retryInMs > 0L) {
                scheduleRetry(retryInMs)
            }
            GeoVaultCaptureLog.w(config.tag, "dispatchStart blocked by min gap retryInMs=$retryInMs")
            return Decision(allowed = false, retryInMs = retryInMs, reason = "min_gap")
        }
        prefs.edit().putLong(KEY_LAST_ATTEMPT_ELAPSED_MS, now).apply()
        val intent = startIntentFactory()
        return try {
            starter.start(appContext, intent)
            prefs.edit()
                .putInt(KEY_FAILURE_COUNT, 0)
                .putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L)
                .apply()
            GeoVaultCaptureLog.i(config.tag, "dispatchStart success reason=$reason")
            Decision(allowed = true, reason = "start_dispatched:$reason")
        } catch (error: Exception) {
            val failureCount = (prefs.getInt(KEY_FAILURE_COUNT, 0) + 1).coerceAtMost(config.maxRetryAttempts)
            val retryDelay = computeRetryDelay(failureCount)
            val blocked = if (retryDelay > 0L) elapsedClock() + retryDelay else 0L
            val exhausted = failureCount >= config.maxRetryAttempts || retryDelay <= 0L
            prefs.edit()
                .putInt(KEY_FAILURE_COUNT, failureCount)
                .putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, blocked)
                .apply()
            if (retryDelay > 0L) {
                scheduleRetry(retryDelay)
            }
            val suffix = if (error is ForegroundServiceStartNotAllowedException) {
                "fgs_denied"
            } else {
                error::class.java.simpleName
            }
            GeoVaultCaptureLog.e(
                config.tag,
                "dispatchStart failed reason=$reason failureCount=$failureCount retryDelay=$retryDelay " +
                    "blockedUntil=$blocked suffix=$suffix",
                error,
            )
            Decision(
                allowed = false,
                retryInMs = retryDelay,
                reason = "start_failed_$suffix",
                exhausted = exhausted,
            )
        }
    }

    fun cancelRetry() {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(retryPendingIntent())
        prefs.edit()
            .putInt(KEY_FAILURE_COUNT, 0)
            .putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L)
            .apply()
        GeoVaultCaptureLog.i(config.tag, "cancelRetry")
    }

    fun reset() {
        cancelRetry()
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_ELAPSED_MS, 0L)
            .apply()
    }

    private fun sanitizePersistedElapsedState(
        now: Long,
        blockedUntil: Long,
        lastAttempt: Long,
    ): Pair<Long, Long> {
        var sanitizedBlockedUntil = blockedUntil
        var sanitizedLastAttempt = lastAttempt
        var changed = false
        if (sanitizedLastAttempt < 0L || sanitizedLastAttempt > now) {
            sanitizedLastAttempt = 0L
            changed = true
        }
        if (sanitizedBlockedUntil < 0L || sanitizedBlockedUntil > now + config.maxRetryBackoffMs) {
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
                config.tag,
                "reset invalid elapsed state now=$now blockedUntil=$blockedUntil lastAttempt=$lastAttempt",
            )
        }
        return sanitizedBlockedUntil to sanitizedLastAttempt
    }

    private fun computeRetryDelay(failureCount: Int): Long {
        if (failureCount <= 0 || failureCount >= config.maxRetryAttempts) return 0L
        return min(config.baseRetryBackoffMs * (1L shl (failureCount - 1)), config.maxRetryBackoffMs)
    }

    private fun scheduleRetry(retryInMs: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            elapsedClock() + retryInMs,
            retryPendingIntent(),
        )
        GeoVaultCaptureLog.i(config.tag, "scheduleRetry retryInMs=$retryInMs")
    }

    private fun retryPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            appContext,
            config.retryRequestCode,
            startIntentFactory(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val DEFAULT_MIN_ATTEMPT_GAP_MS = 1_500L
        const val DEFAULT_BASE_RETRY_BACKOFF_MS = 2_000L
        const val DEFAULT_MAX_RETRY_BACKOFF_MS = 30_000L
        const val DEFAULT_MAX_RETRY_ATTEMPTS = 5
        private const val KEY_LAST_ATTEMPT_ELAPSED_MS = "last_attempt_elapsed_ms"
        private const val KEY_BLOCKED_UNTIL_ELAPSED_MS = "blocked_until_elapsed_ms"
        private const val KEY_FAILURE_COUNT = "failure_count"

        val DefaultForegroundServiceStarter = ForegroundServiceStarter { context, intent ->
            context.startForegroundService(intent)
        }
    }
}
