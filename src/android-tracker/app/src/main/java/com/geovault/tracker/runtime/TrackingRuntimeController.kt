package com.geovault.tracker.runtime

import android.app.AlarmManager
import android.content.Context
import android.os.PowerManager

data class StrictPrereqStatus(
    val hasExactAlarmAccess: Boolean,
    val hasBatteryOptimizationExemption: Boolean
) {
    val isReady: Boolean get() = hasExactAlarmAccess && hasBatteryOptimizationExemption
}

class TrackingRuntimeController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val telemetry = RuntimeTelemetry(appContext)
    private val runtimeFacade = TrackingRuntimeFacade.get(appContext)

    fun handle(command: RuntimeCommand): RuntimeCommandResult {
        return runtimeFacade.handleCommand(command).commandResult
            ?: RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "orchestrator_no_result")
    }

    fun handleWatchdogTick(request: WatchdogRecoveryRequest): RuntimeCommandResult {
        return runtimeFacade
            .handleWatchdogTick(request)
            .commandResult
            ?: RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "orchestrator_no_result")
    }

    fun markHeartbeat() {
        runtimeFacade.handleServiceEvent(
            RuntimeServiceEvent(
                type = RuntimeServiceEventType.HEARTBEAT,
                trigger = RuntimeTrigger.UNKNOWN,
                reason = "service_heartbeat"
            )
        )
    }

    fun markTrackingStarted(trigger: RuntimeTrigger) {
        runtimeFacade.handleServiceEvent(
            RuntimeServiceEvent(
                type = RuntimeServiceEventType.TRACKING_STARTED,
                trigger = trigger,
                reason = "tracking_started"
            )
        )
    }

    fun markIntentionalStop(reason: String) {
        runtimeFacade.handleServiceEvent(
            RuntimeServiceEvent(
                type = RuntimeServiceEventType.TRACKING_STOPPED,
                trigger = RuntimeTrigger.EXPLICIT_STOP,
                reason = reason
            )
        )
    }

    fun markUnexpectedDestroy(wasTracking: Boolean) {
        if (!wasTracking) return
        runtimeFacade.handleServiceEvent(
            RuntimeServiceEvent(
                type = RuntimeServiceEventType.UNEXPECTED_DESTROY,
                trigger = RuntimeTrigger.UNKNOWN,
                reason = "unexpected_destroy"
            )
        )
    }

    fun recordServiceEvent(event: RuntimeServiceEvent) {
        runtimeFacade.handleServiceEvent(event)
    }

    fun ensureWatchdogScheduled() {
        runtimeFacade.scheduleWatchdog(reason = "ensure_watchdog")
        telemetry.decision("ensure_watchdog", "scheduled=true")
    }

    fun cancelWatchdog() {
        runtimeFacade.cancelWatchdog(reason = "cancel_watchdog")
        telemetry.decision("cancel_watchdog", "scheduled=false")
    }

    fun evaluateStrictPrerequisites(): StrictPrereqStatus {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val canExact = try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: SecurityException) {
            false
        }
        val batteryExempt = powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        val status = StrictPrereqStatus(canExact, batteryExempt)
        telemetry.decision(
            "strict_prerequisites",
            "hasExactAlarmAccess=${status.hasExactAlarmAccess} hasBatteryOptimizationExemption=${status.hasBatteryOptimizationExemption} ready=${status.isReady}"
        )
        return status
    }

    fun dumpTelemetry(reason: String) {
        telemetry.dumpToLogcat(reason)
    }

    companion object {
        @Volatile
        private var INSTANCE: TrackingRuntimeController? = null

        fun get(context: Context): TrackingRuntimeController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackingRuntimeController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
