package com.geovault.tracker.runtime

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.geovault.tracker.TrackingService

data class RuntimeCommandResult(
    val action: RuntimeActionType,
    val reason: String,
    val startGateDecision: StartGateDecision? = null
)

data class StrictPrereqStatus(
    val hasExactAlarmAccess: Boolean,
    val hasBatteryOptimizationExemption: Boolean
) {
    val isReady: Boolean get() = hasExactAlarmAccess && hasBatteryOptimizationExemption
}

class TrackingRuntimeController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val stateStore = RuntimeStateStore(appContext)
    private val telemetry = RuntimeTelemetry(appContext)
    private val policy = LifecyclePolicyEngine()
    private val startGate = ServiceStartGate(appContext)
    private val scheduler = WatchdogScheduler(appContext)
    private val supervisor = TrackingHealthSupervisor(appContext)

    fun handle(command: RuntimeCommand): RuntimeCommandResult {
        val state = stateStore.read()
        val decision = policy.evaluate(state, command)
        val action = decision.action
        telemetry.decision(
            name = "policy",
            details = "cmdType=${command.type} trigger=${command.trigger} cmdReason=${command.reason} action=${action.type} actionReason=${action.reason}"
        )
        telemetry.transition(
            name = "policy_apply",
            fromState = state,
            toState = decision.nextState
        )
        stateStore.update { decision.nextState }
        telemetry.event(
            name = "command",
            details = "type=${command.type} trigger=${command.trigger} action=${action.type} reason=${action.reason}"
        )
        return when (action.type) {
            RuntimeActionType.DISPATCH_START -> {
                val gateDecision = startGate.dispatchStart(command.trigger, action.reason)
                if (gateDecision.allowed) {
                    val before = stateStore.read()
                    val after = stateStore.update {
                        it.copy(
                            lifecycleState = RuntimeLifecycleState.ACTIVE,
                            shouldBeRunning = true,
                            lastIntentionalStop = false,
                            lastFailure = null,
                            lastTransitionAtMs = System.currentTimeMillis()
                        )
                    }
                    telemetry.transition("start_gate_success", before, after)
                } else {
                    val before = stateStore.read()
                    val after = stateStore.update {
                        it.copy(
                            lifecycleState = RuntimeLifecycleState.DEGRADED,
                            lastFailure = RuntimeFailure(
                                clazz = if (gateDecision.reason.contains("failed")) RuntimeFailureClass.TRANSIENT else RuntimeFailureClass.POLICY_DENIED,
                                reason = gateDecision.reason
                            ),
                            lastTransitionAtMs = System.currentTimeMillis()
                        )
                    }
                    telemetry.transition("start_gate_failure", before, after)
                }
                telemetry.decision(
                    "start_gate",
                    "allowed=${gateDecision.allowed} retryInMs=${gateDecision.retryInMs} reason=${gateDecision.reason}"
                )
                RuntimeCommandResult(
                    action = action.type,
                    reason = action.reason,
                    startGateDecision = gateDecision
                )
            }
            RuntimeActionType.DISPATCH_STOP -> {
                appContext.startService(
                    Intent(appContext, TrackingService::class.java).apply {
                        this.action = TrackingService.ACTION_STOP
                        setPackage(appContext.packageName)
                    }
                )
                stateStore.update {
                    it.copy(
                        lifecycleState = RuntimeLifecycleState.IDLE,
                        shouldBeRunning = false,
                        lastIntentionalStop = true,
                        lastTransitionAtMs = System.currentTimeMillis()
                    )
                }
                telemetry.decision("dispatch_stop", "reason=${action.reason}")
                RuntimeCommandResult(action = action.type, reason = action.reason)
            }
            RuntimeActionType.RESHOW_FOREGROUND -> {
                appContext.startService(
                    Intent(appContext, TrackingService::class.java).apply {
                        this.action = TrackingService.ACTION_RESHOW_FOREGROUND
                        setPackage(appContext.packageName)
                    }
                )
                telemetry.decision("reshow_foreground", "reason=${action.reason}")
                RuntimeCommandResult(action = action.type, reason = action.reason)
            }
            RuntimeActionType.NOOP -> {
                telemetry.decision("noop", "reason=${action.reason}")
                RuntimeCommandResult(action = action.type, reason = action.reason)
            }
        }
    }

    fun handleWatchdogTick(restartTrackingIfKilled: Boolean, wasTrackingBeforeExit: Boolean): RuntimeCommandResult {
        val desiredRunning = restartTrackingIfKilled && wasTrackingBeforeExit
        telemetry.decision(
            "watchdog_tick",
            "restartTrackingIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit desiredRunning=$desiredRunning"
        )
        if (!desiredRunning) {
            stateStore.update {
                it.copy(
                    shouldBeRunning = false,
                    lifecycleState = RuntimeLifecycleState.IDLE,
                    lastIntentionalStop = true,
                    lastTransitionAtMs = System.currentTimeMillis()
                )
            }
            scheduler.cancel()
            telemetry.event("watchdog", "disabled restartTrackingIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit")
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "watchdog_disabled")
        }
        stateStore.update {
            it.copy(
                shouldBeRunning = true,
                lastIntentionalStop = false
            )
        }
        val health = supervisor.evaluate(stateStore.read())
        telemetry.event("watchdog", "healthy=${health.isHealthy} shouldRecover=${health.shouldRecover} reason=${health.reason}")
        val result = if (health.shouldRecover) {
            handle(
                RuntimeCommand(
                    type = RuntimeCommandType.RECOVER,
                    trigger = RuntimeTrigger.WATCHDOG_TICK,
                    reason = health.reason
                )
            )
        } else {
            RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = health.reason)
        }
        scheduler.schedule()
        telemetry.decision("watchdog_schedule", "scheduledNextTick=true resultAction=${result.action} resultReason=${result.reason}")
        return result
    }

    fun markHeartbeat() {
        handle(RuntimeCommand(RuntimeCommandType.HEARTBEAT, RuntimeTrigger.UNKNOWN, "service_heartbeat"))
    }

    fun markTrackingStarted(trigger: RuntimeTrigger) {
        stateStore.update {
            it.copy(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true,
                lastIntentionalStop = false,
                lastStartTrigger = trigger,
                lastFailure = null,
                lastTransitionAtMs = System.currentTimeMillis(),
                lastHeartbeatAtMs = System.currentTimeMillis()
            )
        }
        scheduler.schedule()
        telemetry.event("tracking_started", "trigger=$trigger")
    }

    fun markIntentionalStop(reason: String) {
        stateStore.update {
            it.copy(
                lifecycleState = RuntimeLifecycleState.IDLE,
                shouldBeRunning = false,
                lastIntentionalStop = true,
                lastFailure = null,
                lastTransitionAtMs = System.currentTimeMillis()
            )
        }
        scheduler.cancel()
        telemetry.event("intentional_stop", "reason=$reason")
    }

    fun markUnexpectedDestroy(wasTracking: Boolean) {
        if (!wasTracking) return
        stateStore.update {
            it.copy(
                lifecycleState = RuntimeLifecycleState.RECOVERING,
                shouldBeRunning = true,
                lastIntentionalStop = false,
                lastTransitionAtMs = System.currentTimeMillis()
            )
        }
        scheduler.schedule()
        telemetry.event("unexpected_destroy", "wasTracking=$wasTracking")
    }

    fun ensureWatchdogScheduled() {
        scheduler.schedule()
        telemetry.decision("ensure_watchdog", "scheduled=true")
    }

    fun cancelWatchdog() {
        scheduler.cancel()
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
