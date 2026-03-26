package com.geovault.tracker.startup

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.geovault.tracker.TrackingService
import com.geovault.tracker.runtime.RuntimeCommand
import com.geovault.tracker.runtime.RuntimeCommandType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import kotlin.math.min

object TrackingServiceLaunchGate {
    private const val TAG = "TrackingLaunchGate"
    private const val PREFS_NAME = "tracking_launch_gate"
    private const val KEY_LAST_ATTEMPT_ELAPSED_MS = "last_attempt_elapsed_ms"
    private const val KEY_BLOCKED_UNTIL_ELAPSED_MS = "blocked_until_elapsed_ms"
    private const val KEY_FAILURE_COUNT = "failure_count"
    private const val KEY_LAST_TRIGGER = "last_trigger"
    private const val MIN_ATTEMPT_GAP_MS = 1500L
    private const val BASE_RETRY_BACKOFF_MS = 2000L
    private const val MAX_RETRY_BACKOFF_MS = 30_000L
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val RETRY_REQUEST_CODE = 22001

    data class LaunchDecision(
        val allowed: Boolean,
        val retryInMs: Long = 0L,
        val reason: String
    )

    internal fun computeRetryDelayMs(failureCount: Int): Long {
        if (failureCount <= 0 || failureCount >= MAX_RETRY_ATTEMPTS) return 0L
        val exponentialBackoff = BASE_RETRY_BACKOFF_MS * (1L shl (failureCount - 1))
        return min(exponentialBackoff, MAX_RETRY_BACKOFF_MS)
    }

    internal fun evaluateDispatchEligibility(
        nowElapsedMs: Long,
        lastAttemptElapsedMs: Long,
        blockedUntilElapsedMs: Long
    ): LaunchDecision {
        if (blockedUntilElapsedMs > nowElapsedMs) {
            return LaunchDecision(
                allowed = false,
                retryInMs = blockedUntilElapsedMs - nowElapsedMs,
                reason = "blocked_backoff"
            )
        }
        val sinceLastAttemptMs = nowElapsedMs - lastAttemptElapsedMs
        if (lastAttemptElapsedMs > 0L && sinceLastAttemptMs < MIN_ATTEMPT_GAP_MS) {
            return LaunchDecision(
                allowed = false,
                retryInMs = MIN_ATTEMPT_GAP_MS - sinceLastAttemptMs,
                reason = "min_gap"
            )
        }
        return LaunchDecision(
            allowed = true,
            reason = "allowed"
        )
    }

    @JvmStatic
    fun dispatchStart(context: Context, trigger: String): LaunchDecision {
        val runtimeResult = TrackingRuntimeController.get(context).handle(
            RuntimeCommand(
                type = RuntimeCommandType.START,
                trigger = mapTrigger(trigger),
                reason = trigger
            )
        )
        val runtimeGate = runtimeResult.startGateDecision
        if (runtimeResult.action == com.geovault.tracker.runtime.RuntimeActionType.DISPATCH_START && runtimeGate != null) {
            return LaunchDecision(
                allowed = runtimeGate.allowed,
                retryInMs = runtimeGate.retryInMs,
                reason = runtimeGate.reason
            )
        }
        if (runtimeResult.action == com.geovault.tracker.runtime.RuntimeActionType.NOOP) {
            return LaunchDecision(
                allowed = false,
                retryInMs = 0L,
                reason = runtimeResult.reason
            )
        }

        val appContext = context.applicationContext
        val decision = beforeLaunchAttempt(appContext, trigger)
        if (!decision.allowed) {
            if (decision.retryInMs > 0L) {
                scheduleRetry(appContext, decision.retryInMs)
            }
            return decision
        }
        val intent = Intent(appContext, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            setPackage(appContext.packageName)
        }
        return try {
            appContext.startForegroundService(intent)
            onLaunchSuccess(appContext)
            LaunchDecision(
                allowed = true,
                reason = "start_dispatched"
            )
        } catch (error: Exception) {
            val retryDelayMs = onLaunchFailure(appContext, trigger, error)
            if (retryDelayMs > 0L) {
                scheduleRetry(appContext, retryDelayMs)
            }
            LaunchDecision(
                allowed = false,
                retryInMs = retryDelayMs,
                reason = "start_failed_${error::class.java.simpleName}"
            )
        }
    }

    private fun mapTrigger(trigger: String): RuntimeTrigger {
        return when {
            trigger.startsWith("boot:") -> RuntimeTrigger.BOOT
            trigger.contains("watchdog") -> RuntimeTrigger.WATCHDOG_TICK
            trigger.contains("resume_after_kill") -> RuntimeTrigger.MAIN_RESUME_AFTER_KILL
            trigger.contains("start_on_launch") -> RuntimeTrigger.MAIN_START_ON_LAUNCH
            else -> RuntimeTrigger.EXPLICIT_START
        }
    }

    private fun beforeLaunchAttempt(context: Context, trigger: String): LaunchDecision {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val prefs = prefs(context)
        val blockedUntilElapsedMs = prefs.getLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L)
        val lastAttemptElapsedMs = prefs.getLong(KEY_LAST_ATTEMPT_ELAPSED_MS, 0L)
        val decision = evaluateDispatchEligibility(
            nowElapsedMs = nowElapsedMs,
            lastAttemptElapsedMs = lastAttemptElapsedMs,
            blockedUntilElapsedMs = blockedUntilElapsedMs
        )
        if (!decision.allowed) {
            Log.w(
                TAG,
                "Launch blocked trigger=$trigger reason=${decision.reason} retryInMs=${decision.retryInMs} lastTrigger=${prefs.getString(KEY_LAST_TRIGGER, "")}"
            )
            return decision
        }
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_ELAPSED_MS, nowElapsedMs)
            .putString(KEY_LAST_TRIGGER, trigger)
            .apply()
        return LaunchDecision(
            allowed = true,
            reason = "allowed"
        )
    }

    private fun onLaunchSuccess(context: Context) {
        Log.i(TAG, "Launch dispatched successfully")
        prefs(context).edit()
            .putInt(KEY_FAILURE_COUNT, 0)
            .putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, 0L)
            .apply()
    }

    private fun onLaunchFailure(context: Context, trigger: String, error: Exception): Long {
        val prefs = prefs(context)
        val failureCount = (prefs.getInt(KEY_FAILURE_COUNT, 0) + 1).coerceAtMost(MAX_RETRY_ATTEMPTS)
        val isBackgroundFgsDenied = error is ForegroundServiceStartNotAllowedException
        val retryDelayMs = if (failureCount >= MAX_RETRY_ATTEMPTS) {
            0L
        } else {
            computeRetryDelayMs(failureCount)
        }
        val blockedUntilMs = if (retryDelayMs > 0L) {
            SystemClock.elapsedRealtime() + retryDelayMs
        } else {
            0L
        }
        prefs.edit()
            .putInt(KEY_FAILURE_COUNT, failureCount)
            .putLong(KEY_BLOCKED_UNTIL_ELAPSED_MS, blockedUntilMs)
            .apply()
        Log.e(
            TAG,
            "Launch failed trigger=$trigger failures=$failureCount retryInMs=$retryDelayMs backgroundDenied=$isBackgroundFgsDenied",
            error
        )
        return retryDelayMs
    }

    private fun scheduleRetry(context: Context, retryInMs: Long) {
        if (retryInMs <= 0L) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val retryIntent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getService(
            context,
            RETRY_REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtElapsedMs = SystemClock.elapsedRealtime() + retryInMs
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtElapsedMs,
            pendingIntent
        )
        Log.i(TAG, "Scheduled launch retry in ${retryInMs}ms")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
