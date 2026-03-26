package com.geovault.tracker

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.startup.TrackingServiceLaunchGate

object TrackingRecoveryCoordinator {
    private const val TAG = "TrackingRecovery"
    private const val APP_OP_SCHEDULE_EXACT_ALARM = "android:schedule_exact_alarm"
    @Volatile
    private var lastExactAlarmStateLog: String? = null
    @Volatile
    private var lastRecoveryDecisionLog: String? = null
    @Volatile
    private var lastTrackingPrereqLog: String? = null
    private const val PREFS_NAME = "tracking_recovery_state"
    private const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
    private const val KEY_LAST_START_REQUEST_MS = "last_start_request_ms"
    private const val KEY_LAST_STOP_WAS_INTENTIONAL = "last_stop_was_intentional"
    private const val KEY_LAST_STOP_REASON = "last_stop_reason"
    private const val KEY_RECOVERY_WINDOW_START_MS = "recovery_window_start_ms"
    private const val KEY_CONSECUTIVE_STALE_TICKS = "consecutive_stale_ticks"
    private const val KEY_ATTEMPT_NOTIFICATION_SHOWN = "attempt_notification_shown"
    private const val KEY_FAILURE_NOTIFICATION_SHOWN = "failure_notification_shown"
    private const val KEY_TELEMETRY_RING = "recovery_telemetry_ring"
    private const val MAX_TELEMETRY_ENTRIES = 300

    const val ACTION_RECOVERY_TICK = "com.geovault.tracker.ACTION_RECOVERY_TICK"
    const val ACTION_OPEN_APP_FROM_RECOVERY = "com.geovault.tracker.ACTION_OPEN_APP_FROM_RECOVERY"
    const val CHANNEL_ID_RECOVERY = "tracking_recovery_alerts"

    // Aggressive best-effort cadence; the OS can still throttle in idle modes.
    const val RECOVERY_INTERVAL_MS = 5_000L
    const val RECOVERY_TARGET_MS = 30_000L
    const val RECOVERY_FAILURE_MS = 60_000L
    const val HEARTBEAT_STALE_MS = 30_000L
    const val REQUIRED_CONSECUTIVE_STALE_TICKS = 2
    private const val START_REQUEST_MIN_GAP_MS = 1_500L
    private const val NOTIFICATION_ID_ATTEMPT = 9101
    private const val NOTIFICATION_ID_FAILURE = 9102

    enum class RecoveryState {
        HEALTHY,
        PENDING_STALE_CONFIRMATION,
        READY,
        BLOCKED_PREREQ,
        THROTTLED,
        RESTARTED,
        ESCALATED,
        DISABLED
    }

    data class StrictPrereqStatus(
        val hasExactAlarmAccess: Boolean,
        val hasBatteryOptimizationExemption: Boolean
    ) {
        val isReady: Boolean get() = hasExactAlarmAccess && hasBatteryOptimizationExemption
    }

    data class RecoveryEvaluation(
        val state: RecoveryState,
        val shouldStartService: Boolean,
        val shouldKeepWatchdog: Boolean,
        val shouldShowFailureNotification: Boolean,
        val reason: String
    )

    private data class TrackingPrereqCheck(
        val canStart: Boolean,
        val reason: String
    )

    @JvmStatic
    fun evaluateStrictPrerequisites(context: Context): StrictPrereqStatus {
        return StrictPrereqStatus(
            hasExactAlarmAccess = hasExactAlarmAccess(context),
            hasBatteryOptimizationExemption = hasBatteryOptimizationExemption(context)
        )
    }

    @JvmStatic
    fun hasExactAlarmAccess(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canScheduleExact = alarmManager.canScheduleExactAlarmsCompat()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            APP_OP_SCHEDULE_EXACT_ALARM,
            context.applicationInfo.uid,
            context.packageName
        )
        val batteryExempt = hasBatteryOptimizationExemption(context)
        logExactAlarmStateIfChanged(canScheduleExact, mode, batteryExempt)
        return canScheduleExact
    }

    private fun logExactAlarmStateIfChanged(
        canScheduleExact: Boolean,
        appOpMode: Int,
        batteryExempt: Boolean
    ) {
        val signature = "can=$canScheduleExact;mode=$appOpMode;batteryExempt=$batteryExempt"
        if (lastExactAlarmStateLog == signature) return
        lastExactAlarmStateLog = signature
        Log.i(
            TAG,
            "Exact alarm state (Recovery): canScheduleExact=$canScheduleExact appOpMode=${appOpModeName(appOpMode)} batteryExempt=$batteryExempt"
        )
    }

    private fun appOpModeName(mode: Int): String {
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "ignored"
            AppOpsManager.MODE_ERRORED -> "errored"
            AppOpsManager.MODE_DEFAULT -> "default"
            else -> "unknown($mode)"
        }
    }

    @JvmStatic
    fun hasBatteryOptimizationExemption(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @JvmStatic
    fun markTrackingStarted(context: Context) {
        TrackingRuntimeController.get(context).markTrackingStarted(RuntimeTrigger.EXPLICIT_START)
        val now = System.currentTimeMillis()
        Log.i(TAG, "markTrackingStarted at=$now")
        recordTelemetry(context, "markTrackingStarted at=$now")
        prefs(context).edit()
            .putBoolean(KEY_LAST_STOP_WAS_INTENTIONAL, false)
            .putString(KEY_LAST_STOP_REASON, "tracking_started")
            .putLong(KEY_LAST_HEARTBEAT_MS, now)
            .remove(KEY_RECOVERY_WINDOW_START_MS)
            .remove(KEY_CONSECUTIVE_STALE_TICKS)
            .putBoolean(KEY_ATTEMPT_NOTIFICATION_SHOWN, false)
            .putBoolean(KEY_FAILURE_NOTIFICATION_SHOWN, false)
            .apply()
        cancelRecoveryNotifications(context)
        ensureWatchdogScheduled(context)
    }

    @JvmStatic
    fun markHeartbeat(context: Context) {
        TrackingRuntimeController.get(context).markHeartbeat()
        prefs(context).edit()
            .putLong(KEY_LAST_HEARTBEAT_MS, System.currentTimeMillis())
            .apply()
    }

    @JvmStatic
    fun markIntentionalStop(context: Context, reason: String = "intentional_stop") {
        TrackingRuntimeController.get(context).markIntentionalStop(reason)
        Log.i(TAG, "markIntentionalStop reason=$reason")
        recordTelemetry(context, "markIntentionalStop reason=$reason")
        prefs(context).edit()
            .putBoolean(KEY_LAST_STOP_WAS_INTENTIONAL, true)
            .putString(KEY_LAST_STOP_REASON, reason)
            .remove(KEY_LAST_HEARTBEAT_MS)
            .remove(KEY_RECOVERY_WINDOW_START_MS)
            .remove(KEY_CONSECUTIVE_STALE_TICKS)
            .putBoolean(KEY_ATTEMPT_NOTIFICATION_SHOWN, false)
            .putBoolean(KEY_FAILURE_NOTIFICATION_SHOWN, false)
            .apply()
        cancelRecoveryNotifications(context)
        cancelWatchdog(context)
    }

    @JvmStatic
    fun markUnexpectedDestroy(context: Context, wasTracking: Boolean) {
        TrackingRuntimeController.get(context).markUnexpectedDestroy(wasTracking)
        if (!wasTracking) {
            Log.d(TAG, "markUnexpectedDestroy ignored (wasTracking=false)")
            recordTelemetry(context, "markUnexpectedDestroy ignored wasTracking=false")
            return
        }
        val now = System.currentTimeMillis()
        Log.w(TAG, "markUnexpectedDestroy at=$now")
        recordTelemetry(context, "markUnexpectedDestroy at=$now")
        prefs(context).edit()
            .putBoolean(KEY_LAST_STOP_WAS_INTENTIONAL, false)
            .putString(KEY_LAST_STOP_REASON, "unexpected_destroy")
            .putLong(KEY_RECOVERY_WINDOW_START_MS, now)
            .putInt(KEY_CONSECUTIVE_STALE_TICKS, 0)
            .putBoolean(KEY_FAILURE_NOTIFICATION_SHOWN, false)
            .apply()
        showAttemptingRestartNotification(context)
        ensureWatchdogScheduled(context)
    }

    @JvmStatic
    fun ensureWatchdogScheduled(context: Context) {
        TrackingRuntimeController.get(context).ensureWatchdogScheduled()
        val triggerAt = SystemClock.elapsedRealtime() + RECOVERY_INTERVAL_MS
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = recoveryPendingIntent(context)
        if (alarmManager.canScheduleExactAlarmsCompat()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
            Log.d(TAG, "Watchdog scheduled mode=exact triggerInMs=$RECOVERY_INTERVAL_MS")
            recordTelemetry(context, "watchdog scheduled mode=exact triggerInMs=$RECOVERY_INTERVAL_MS")
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
            Log.d(TAG, "Watchdog scheduled mode=inexact triggerInMs=$RECOVERY_INTERVAL_MS")
            recordTelemetry(context, "watchdog scheduled mode=inexact triggerInMs=$RECOVERY_INTERVAL_MS")
        }
    }

    @JvmStatic
    fun cancelWatchdog(context: Context) {
        TrackingRuntimeController.get(context).cancelWatchdog()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(recoveryPendingIntent(context))
        Log.d(TAG, "Watchdog canceled")
        recordTelemetry(context, "watchdog canceled")
    }

    @JvmStatic
    fun evaluateRecovery(
        nowMs: Long,
        wasTrackingBeforeExit: Boolean,
        restartTrackingIfKilled: Boolean,
        lastHeartbeatMs: Long,
        consecutiveStaleTicks: Int,
        lastStopWasIntentional: Boolean,
        canStartNow: Boolean,
        strictPrereqsReady: Boolean,
        exactAlarmAvailable: Boolean,
        recoveryWindowStartMs: Long,
        failureNotificationShown: Boolean,
        requiredConsecutiveStaleTicks: Int = REQUIRED_CONSECUTIVE_STALE_TICKS
    ): RecoveryEvaluation {
        if (!restartTrackingIfKilled || !wasTrackingBeforeExit) {
            return RecoveryEvaluation(
                state = RecoveryState.DISABLED,
                shouldStartService = false,
                shouldKeepWatchdog = false,
                shouldShowFailureNotification = false,
                reason = "restart disabled or tracking not desired"
            )
        }
        if (lastStopWasIntentional) {
            return RecoveryEvaluation(
                state = RecoveryState.DISABLED,
                shouldStartService = false,
                shouldKeepWatchdog = false,
                shouldShowFailureNotification = false,
                reason = "last stop was intentional"
            )
        }
        val heartbeatStale = lastHeartbeatMs <= 0L || (nowMs - lastHeartbeatMs) > HEARTBEAT_STALE_MS
        if (!heartbeatStale) {
            return RecoveryEvaluation(
                state = RecoveryState.HEALTHY,
                shouldStartService = false,
                shouldKeepWatchdog = true,
                shouldShowFailureNotification = false,
                reason = "service heartbeat is fresh"
            )
        }
        if (consecutiveStaleTicks < requiredConsecutiveStaleTicks) {
            return RecoveryEvaluation(
                state = RecoveryState.PENDING_STALE_CONFIRMATION,
                shouldStartService = false,
                shouldKeepWatchdog = true,
                shouldShowFailureNotification = false,
                reason = "stale heartbeat confirmation pending"
            )
        }
        val elapsedRecoveryMs = if (recoveryWindowStartMs > 0L) nowMs - recoveryWindowStartMs else 0L
        val shouldShowFailure = elapsedRecoveryMs >= RECOVERY_FAILURE_MS && !failureNotificationShown

        if (!strictPrereqsReady) {
            return RecoveryEvaluation(
                state = RecoveryState.BLOCKED_PREREQ,
                shouldStartService = false,
                shouldKeepWatchdog = true,
                shouldShowFailureNotification = shouldShowFailure,
                reason = "strict prerequisites missing"
            )
        }

        if (!canStartNow) {
            return RecoveryEvaluation(
                state = RecoveryState.BLOCKED_PREREQ,
                shouldStartService = false,
                shouldKeepWatchdog = true,
                shouldShowFailureNotification = shouldShowFailure,
                reason = "prerequisites not met"
            )
        }
        val state = if (exactAlarmAvailable) RecoveryState.READY else RecoveryState.THROTTLED
        return RecoveryEvaluation(
            state = state,
            shouldStartService = true,
            shouldKeepWatchdog = true,
            shouldShowFailureNotification = shouldShowFailure,
            reason = "heartbeat stale, attempting restart"
        )
    }

    @JvmStatic
    fun handleRecoveryTick(
        context: Context,
        restartTrackingIfKilled: Boolean,
        wasTrackingBeforeExit: Boolean
    ) {
        val runtimeResult = TrackingRuntimeController.get(context).handleWatchdogTick(
            restartTrackingIfKilled = restartTrackingIfKilled,
            wasTrackingBeforeExit = wasTrackingBeforeExit
        )
        Log.i(
            TAG,
            "Runtime watchdog decision action=${runtimeResult.action} reason=${runtimeResult.reason} gate=${runtimeResult.startGateDecision}"
        )
        return

        val now = System.currentTimeMillis()
        val strictStatus = evaluateStrictPrerequisites(context)
        val lastStopWasIntentional = prefs(context).getBoolean(KEY_LAST_STOP_WAS_INTENTIONAL, false)
        val lastHeartbeatMs = prefs(context).getLong(KEY_LAST_HEARTBEAT_MS, 0L)
        val recoveryWindowStartMs = prefs(context).getLong(KEY_RECOVERY_WINDOW_START_MS, 0L)
        val consecutiveStaleTicks = prefs(context).getInt(KEY_CONSECUTIVE_STALE_TICKS, 0)
        val failureNotificationShown = prefs(context).getBoolean(KEY_FAILURE_NOTIFICATION_SHOWN, false)
        val exactAlarmAvailable = hasExactAlarmAccess(context)
        val trackingPrereq = checkTrackingPrerequisites(context)
        val heartbeatStale = lastHeartbeatMs <= 0L || (now - lastHeartbeatMs) > HEARTBEAT_STALE_MS
        val updatedConsecutiveStaleTicks = if (heartbeatStale) {
            consecutiveStaleTicks + 1
        } else {
            0
        }
        if (updatedConsecutiveStaleTicks != consecutiveStaleTicks) {
            prefs(context).edit().putInt(KEY_CONSECUTIVE_STALE_TICKS, updatedConsecutiveStaleTicks).apply()
        }
        if (logTrackingPrereqIfChanged(trackingPrereq)) {
            recordTelemetry(context, "trackingPrereq canStart=${trackingPrereq.canStart} reason=${trackingPrereq.reason}")
        }
        val evaluation = evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = wasTrackingBeforeExit,
            restartTrackingIfKilled = restartTrackingIfKilled,
            lastHeartbeatMs = lastHeartbeatMs,
            consecutiveStaleTicks = updatedConsecutiveStaleTicks,
            lastStopWasIntentional = lastStopWasIntentional,
            canStartNow = trackingPrereq.canStart,
            strictPrereqsReady = strictStatus.isReady,
            exactAlarmAvailable = exactAlarmAvailable,
            recoveryWindowStartMs = if (recoveryWindowStartMs > 0L) recoveryWindowStartMs else now,
            failureNotificationShown = failureNotificationShown
        )
        val needsRecoveryWindow = evaluation.state == RecoveryState.READY ||
            evaluation.state == RecoveryState.THROTTLED ||
            evaluation.state == RecoveryState.BLOCKED_PREREQ
        if (recoveryWindowStartMs <= 0L && needsRecoveryWindow && !lastStopWasIntentional) {
            // Start and notify only when recovery is actually needed.
            startRecoveryWindow(context, now)
        }
        if (logRecoveryDecisionIfChanged(
            evaluation = evaluation,
            nowMs = now,
            restartTrackingIfKilled = restartTrackingIfKilled,
            wasTrackingBeforeExit = wasTrackingBeforeExit,
            lastStopWasIntentional = lastStopWasIntentional,
            lastHeartbeatMs = lastHeartbeatMs,
            recoveryWindowStartMs = recoveryWindowStartMs,
            consecutiveStaleTicks = updatedConsecutiveStaleTicks,
            strictStatus = strictStatus,
            exactAlarmAvailable = exactAlarmAvailable,
            trackingPrereq = trackingPrereq
        )) {
            val heartbeatAgeMs = if (lastHeartbeatMs > 0L) now - lastHeartbeatMs else -1L
            val recoveryAgeMs = if (recoveryWindowStartMs > 0L) now - recoveryWindowStartMs else -1L
            recordTelemetry(
                context,
                "decision state=${evaluation.state} start=${evaluation.shouldStartService} keepWatchdog=${evaluation.shouldKeepWatchdog} showFailure=${evaluation.shouldShowFailureNotification} reason=${evaluation.reason} heartbeatAgeMs=$heartbeatAgeMs recoveryAgeMs=$recoveryAgeMs staleTicks=$updatedConsecutiveStaleTicks requiredStaleTicks=$REQUIRED_CONSECUTIVE_STALE_TICKS"
            )
        }

        if (!evaluation.shouldKeepWatchdog) {
            cancelWatchdog(context)
            Log.d(TAG, "Watchdog canceled: ${evaluation.reason}")
            return
        }

        if (evaluation.state == RecoveryState.HEALTHY) {
            clearRecoveredState(context)
        }

        if (evaluation.shouldShowFailureNotification) {
            showRestartFailedNotification(context)
        }

        if (evaluation.shouldStartService) {
            maybeStartTrackingService(context, now)
        }
        ensureWatchdogScheduled(context)
    }

    private fun maybeStartTrackingService(context: Context, nowMs: Long) {
        val lastStartRequestMs = prefs(context).getLong(KEY_LAST_START_REQUEST_MS, 0L)
        if (nowMs - lastStartRequestMs < START_REQUEST_MIN_GAP_MS) {
            Log.d(
                TAG,
                "Skipping restart request due to min gap: sinceLastMs=${nowMs - lastStartRequestMs} minGapMs=$START_REQUEST_MIN_GAP_MS"
            )
            recordTelemetry(
                context,
                "restart skipped minGap sinceLastMs=${nowMs - lastStartRequestMs} minGapMs=$START_REQUEST_MIN_GAP_MS"
            )
            return
        }

        prefs(context).edit().putLong(KEY_LAST_START_REQUEST_MS, nowMs).apply()
        val launchDecision = TrackingServiceLaunchGate.dispatchStart(
            context = context,
            trigger = "watchdog_tick"
        )
        Log.i(
            TAG,
            "Watchdog launch decision allowed=${launchDecision.allowed} retryInMs=${launchDecision.retryInMs} reason=${launchDecision.reason}"
        )
        recordTelemetry(
            context,
            "watchdog launch allowed=${launchDecision.allowed} retryInMs=${launchDecision.retryInMs} reason=${launchDecision.reason}"
        )
    }

    private fun startRecoveryWindow(context: Context, nowMs: Long) {
        Log.i(TAG, "Recovery window started at=$nowMs targetMs=$RECOVERY_TARGET_MS failMs=$RECOVERY_FAILURE_MS")
        recordTelemetry(context, "recovery window started at=$nowMs")
        prefs(context).edit()
            .putLong(KEY_RECOVERY_WINDOW_START_MS, nowMs)
            .putBoolean(KEY_FAILURE_NOTIFICATION_SHOWN, false)
            .apply()
        showAttemptingRestartNotification(context)
    }

    private fun clearRecoveredState(context: Context) {
        clearAttemptNotificationIfResolved(context)
        prefs(context).edit()
            .remove(KEY_RECOVERY_WINDOW_START_MS)
            .putInt(KEY_CONSECUTIVE_STALE_TICKS, 0)
            .putBoolean(KEY_FAILURE_NOTIFICATION_SHOWN, false)
            .apply()
    }

    private fun checkTrackingPrerequisites(context: Context): TrackingPrereqCheck {
        if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(context)) {
            return TrackingPrereqCheck(
                canStart = false,
                reason = "tracking permissions missing"
            )
        }
        if (!TrackingService.hasValidSelectedTrackerId(SelectedTrackerPrefs.selectedTrackerId(context))) {
            return TrackingPrereqCheck(
                canStart = false,
                reason = "selected tracker missing or invalid"
            )
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return TrackingPrereqCheck(
                canStart = false,
                reason = "location manager unavailable"
            )
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return TrackingPrereqCheck(
                canStart = false,
                reason = "gps provider disabled"
            )
        }
        return TrackingPrereqCheck(
            canStart = true,
            reason = "all prerequisites satisfied"
        )
    }

    private fun logTrackingPrereqIfChanged(prereq: TrackingPrereqCheck): Boolean {
        val signature = "${prereq.canStart}:${prereq.reason}"
        if (lastTrackingPrereqLog == signature) return false
        lastTrackingPrereqLog = signature
        val level = if (prereq.canStart) Log.INFO else Log.WARN
        Log.println(level, TAG, "Tracking prereq check: canStart=${prereq.canStart} reason=${prereq.reason}")
        return true
    }

    private fun logRecoveryDecisionIfChanged(
        evaluation: RecoveryEvaluation,
        nowMs: Long,
        restartTrackingIfKilled: Boolean,
        wasTrackingBeforeExit: Boolean,
        lastStopWasIntentional: Boolean,
        lastHeartbeatMs: Long,
        recoveryWindowStartMs: Long,
        consecutiveStaleTicks: Int,
        strictStatus: StrictPrereqStatus,
        exactAlarmAvailable: Boolean,
        trackingPrereq: TrackingPrereqCheck
    ): Boolean {
        val heartbeatAgeMs = if (lastHeartbeatMs > 0L) nowMs - lastHeartbeatMs else -1L
        val recoveryAgeMs = if (recoveryWindowStartMs > 0L) nowMs - recoveryWindowStartMs else -1L
        val signature = listOf(
            evaluation.state.name,
            evaluation.shouldStartService.toString(),
            evaluation.shouldKeepWatchdog.toString(),
            evaluation.shouldShowFailureNotification.toString(),
            evaluation.reason,
            restartTrackingIfKilled.toString(),
            wasTrackingBeforeExit.toString(),
            lastStopWasIntentional.toString(),
            strictStatus.isReady.toString(),
            exactAlarmAvailable.toString(),
            trackingPrereq.canStart.toString(),
            trackingPrereq.reason,
            consecutiveStaleTicks.toString(),
            REQUIRED_CONSECUTIVE_STALE_TICKS.toString(),
            heartbeatAgeMs.toString(),
            recoveryAgeMs.toString()
        ).joinToString("|")
        if (lastRecoveryDecisionLog == signature) return false
        lastRecoveryDecisionLog = signature
        Log.i(
            TAG,
            "Recovery decision state=${evaluation.state} start=${evaluation.shouldStartService} keepWatchdog=${evaluation.shouldKeepWatchdog} showFailure=${evaluation.shouldShowFailureNotification} reason=${evaluation.reason} restartIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit intentionalStop=$lastStopWasIntentional strictReady=${strictStatus.isReady} exactAlarmAvailable=$exactAlarmAvailable trackingPrereq=${trackingPrereq.reason} staleTicks=$consecutiveStaleTicks requiredStaleTicks=$REQUIRED_CONSECUTIVE_STALE_TICKS heartbeatAgeMs=$heartbeatAgeMs recoveryAgeMs=$recoveryAgeMs"
        )
        return true
    }

    private fun showAttemptingRestartNotification(context: Context) {
        if (AppForegroundState.isForeground()) {
            Log.d(TAG, "Suppressing attempt notification while app is foreground")
            recordTelemetry(context, "attempt notification suppressed (app foreground)")
            return
        }
        val alreadyShown = prefs(context).getBoolean(KEY_ATTEMPT_NOTIFICATION_SHOWN, false)
        if (alreadyShown) {
            Log.d(TAG, "Attempt notification already shown; skipping duplicate")
            recordTelemetry(context, "attempt notification duplicate skipped")
            return
        }
        notify(
            context = context,
            id = NOTIFICATION_ID_ATTEMPT,
            title = context.getString(R.string.recovery_attempt_title),
            text = context.getString(R.string.recovery_attempt_text),
            ongoing = true
        )
        prefs(context).edit().putBoolean(KEY_ATTEMPT_NOTIFICATION_SHOWN, true).apply()
        Log.i(TAG, "Attempt notification posted")
        recordTelemetry(context, "attempt notification posted")
    }

    private fun showRestartFailedNotification(context: Context) {
        notify(
            context = context,
            id = NOTIFICATION_ID_FAILURE,
            title = context.getString(R.string.recovery_failed_title),
            text = context.getString(R.string.recovery_failed_text),
            ongoing = false
        )
        prefs(context).edit().putBoolean(KEY_FAILURE_NOTIFICATION_SHOWN, true).apply()
        Log.w(TAG, "Failure notification posted")
        recordTelemetry(context, "failure notification posted")
    }

    private fun clearAttemptNotificationIfResolved(context: Context) {
        val alreadyShown = prefs(context).getBoolean(KEY_ATTEMPT_NOTIFICATION_SHOWN, false)
        if (!alreadyShown) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_ATTEMPT)
        prefs(context).edit().putBoolean(KEY_ATTEMPT_NOTIFICATION_SHOWN, false).apply()
        Log.i(TAG, "Attempt notification cleared after healthy recovery")
        recordTelemetry(context, "attempt notification cleared after healthy recovery")
    }

    private fun notify(context: Context, id: Int, title: String, text: String, ongoing: Boolean) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_APP_FROM_RECOVERY
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RECOVERY)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(false)
            .setOngoing(ongoing)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    private fun cancelRecoveryNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_ATTEMPT)
        manager.cancel(NOTIFICATION_ID_FAILURE)
    }

    @JvmStatic
    fun dumpTelemetryToLogcat(context: Context, reason: String = "manual") {
        val entries = prefs(context).getString(KEY_TELEMETRY_RING, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        Log.i(TAG, "Telemetry dump requested reason=$reason entries=${entries.size}")
        if (entries.isEmpty()) {
            Log.i(TAG, "Telemetry dump is empty")
            return
        }
        entries.forEachIndexed { index, entry ->
            Log.i(TAG, "Telemetry[${index + 1}/${entries.size}] $entry")
        }
    }

    @Synchronized
    private fun recordTelemetry(context: Context, event: String) {
        val existing = prefs(context).getString(KEY_TELEMETRY_RING, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toMutableList()
        val now = System.currentTimeMillis()
        existing.add("$now | $event")
        val trimmed = if (existing.size > MAX_TELEMETRY_ENTRIES) {
            existing.takeLast(MAX_TELEMETRY_ENTRIES)
        } else {
            existing
        }
        prefs(context).edit().putString(KEY_TELEMETRY_RING, trimmed.joinToString("\n")).apply()
    }

    @JvmStatic
    fun createRecoveryChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID_RECOVERY,
            context.getString(R.string.recovery_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.recovery_channel_description)
            enableVibration(true)
            setBypassDnd(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
        }
        manager.createNotificationChannel(channel)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun recoveryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TrackingRecoveryReceiver::class.java).apply {
            action = ACTION_RECOVERY_TICK
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            20101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun AlarmManager.canScheduleExactAlarmsCompat(): Boolean {
        return try {
            canScheduleExactAlarms()
        } catch (_: SecurityException) {
            false
        }
    }
}
