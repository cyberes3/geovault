package com.geovault.tracker.runtime

import android.app.Application
import android.content.Context
import android.content.Intent
import com.geovault.common.concurrent.CommandCorrelation
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.service.GeoVaultForegroundStartGate
import com.geovault.common.service.GeoVaultRuntimeWatchdog
import com.geovault.tracker.TrackingRecoveryReceiver
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TrackerRuntimeEngine internal constructor(
    context: Context,
    private val startGate: GeoVaultForegroundStartGate,
    private val watchdog: GeoVaultRuntimeWatchdog,
    private val telemetry: RuntimeTelemetry,
    private val isServiceRunning: () -> Boolean,
    private val correlation: CommandCorrelation,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext

    fun handle(command: TrackerRuntimeCommands): RuntimeCommandResult {
        return when (command) {
            is TrackerRuntimeCommands.Start -> handleStart(command)
            is TrackerRuntimeCommands.Stop -> handleStop(command)
            is TrackerRuntimeCommands.Recover -> handleRecover(command.reason)
            is TrackerRuntimeCommands.ReshowForeground -> handleReshow(command.reason)
            is TrackerRuntimeCommands.Heartbeat -> handleHeartbeat(command.timestampMs)
            is TrackerRuntimeCommands.SwitchRecordingTarget -> handleSwitch(command)
            is TrackerRuntimeCommands.SendManualPoint -> handleManualPoint()
            is TrackerRuntimeCommands.ServiceStarted -> handleServiceStarted(command)
            is TrackerRuntimeCommands.ServiceStopped -> handleServiceStopped(command.reason, command.timestampMs)
            is TrackerRuntimeCommands.StartupFailed -> handleStartupFailed(command.reason, command.timestampMs)
            is TrackerRuntimeCommands.UnexpectedDestroy -> handleUnexpectedDestroy(command)
            is TrackerRuntimeCommands.TaskRemoved -> handleTaskRemoved()
        }
    }

    fun handleWatchdogTick(input: RecoveryInput): RuntimeCommandResult {
        val decision = RecoveryPolicy.decide(input.copy(serviceRunning = isServiceRunning()))
        telemetry.decision("watchdog_tick", "decision=$decision shouldBeRunning=${input.shouldBeRunning}")
        return when (decision) {
            RecoveryDecision.Arm -> {
                watchdog.schedule()
                RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "watchdog_armed")
            }
            is RecoveryDecision.AttemptStart -> {
                handleRecover(decision.reason)
            }
            is RecoveryDecision.Defer -> {
                watchdog.schedule(decision.delayMs)
                RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = decision.reason)
            }
            is RecoveryDecision.Abandon -> {
                if (!isServiceRunning()) {
                    TrackerRuntimeStore.updateOrchestration {
                        it.copy(
                            shouldBeRunning = false,
                            lifecycleState = RuntimeLifecycleState.IDLE,
                            lastTransitionAtMs = nowMs(),
                        )
                    }
                }
                startGate.cancelRetry()
                watchdog.cancel()
                RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = decision.reason)
            }
        }
    }

    fun handleRecoverySource(input: RecoveryInput): RuntimeCommandResult {
        val decision = RecoveryPolicy.decide(input.copy(serviceRunning = isServiceRunning()))
        return when (decision) {
            RecoveryDecision.Arm -> {
                watchdog.schedule()
                RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "armed")
            }
            is RecoveryDecision.AttemptStart -> handle(
                TrackerRuntimeCommands.Start(trigger = decision.trigger, reason = decision.reason),
            )
            is RecoveryDecision.Defer -> {
                watchdog.schedule(decision.delayMs)
                RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = decision.reason)
            }
            is RecoveryDecision.Abandon -> {
                RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = decision.reason)
            }
        }
    }

    fun scheduleWatchdog(delayMs: Long = GeoVaultRuntimeWatchdog.DEFAULT_INTERVAL_MS) {
        watchdog.schedule(delayMs)
        GeoVaultCaptureLog.d(TAG, "watchdog_scheduled")
        RecoveryTelemetry.record(appContext, "watchdog_scheduled")
    }

    fun cancelWatchdog() {
        watchdog.cancel()
        startGate.cancelRetry()
        GeoVaultCaptureLog.d(TAG, "watchdog_canceled")
        RecoveryTelemetry.record(appContext, "watchdog_canceled")
    }

    fun isRunning(): Boolean = isServiceRunning()

    fun armColdStart(application: Application) {
        val settings = TrackerAppServices.from(application).trackerSettingsRepository()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val state = settings.observeState()
                .first { it.loadState != TrackerSettingsLoadState.Loading }
            val result = handleRecoverySource(
                RecoveryInput(
                    source = RecoverySource.ColdStart,
                    shouldBeRunning = TrackerRuntimeStore.value.shouldBeRunning,
                    restartTrackingIfKilled = true,
                    startOnBoot = state.settings.startOnBoot,
                    serviceRunning = isServiceRunning(),
                    settingsLoadState = state.loadState,
                    userUnlocked = true,
                    hasRequiredPermissions = true,
                    gpsProviderEnabled = true,
                    selectedTrackerId = TrackerAppServices.from(application)
                        .catalogSelectionController()
                        .selectedTrackerId(application),
                    lastHeartbeatAtMs = TrackerRuntimeStore.value.orchestration.lastHeartbeatAtMs,
                    nowMs = nowMs(),
                ),
            )
            GeoVaultCaptureLog.i(TAG, "Cold-start recovery reason=${result.reason}")
        }
    }

    fun switchRecordingTarget(trackerId: String): RuntimeCommandResult {
        val token = correlation.next(TrackerRuntimeCommands.CORRELATION_RECORDING)
        return handle(TrackerRuntimeCommands.SwitchRecordingTarget(trackerId = trackerId, token = token))
    }

    private fun handleStart(command: TrackerRuntimeCommands.Start): RuntimeCommandResult {
        if (command.token != null && !correlation.isCurrent(command.token)) {
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "stale_token")
        }
        if (isServiceRunning()) {
            TrackerRuntimeStore.updateOrchestration {
                it.copy(
                    lifecycleState = RuntimeLifecycleState.ACTIVE,
                    shouldBeRunning = true,
                    lastIntentionalStop = false,
                    lastStartTrigger = command.trigger,
                    lastTransitionAtMs = nowMs(),
                )
            }
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "already_running")
        }
        TrackerRuntimeStore.updateOrchestration {
            it.copy(
                lifecycleState = RuntimeLifecycleState.STARTING,
                shouldBeRunning = true,
                lastIntentionalStop = false,
                lastStartTrigger = command.trigger,
                lastFailure = null,
                lastTransitionAtMs = nowMs(),
            )
        }
        val gate = startGate.dispatchStart(command.reason)
        val mapped = StartGateDecision(
            allowed = gate.allowed,
            retryInMs = gate.retryInMs,
            reason = gate.reason,
        )
        return if (gate.allowed) {
            TrackerRuntimeStore.updateOrchestration {
                it.copy(
                    lifecycleState = RuntimeLifecycleState.ACTIVE,
                    shouldBeRunning = true,
                    lastIntentionalStop = false,
                    lastFailure = null,
                    lastTransitionAtMs = nowMs(),
                )
            }
            watchdog.schedule()
            telemetry.decision("start_gate", "allowed=true reason=${gate.reason}")
            RuntimeCommandResult(
                action = RuntimeActionType.DISPATCH_START,
                reason = command.reason,
                startGateDecision = mapped,
            )
        } else {
            TrackerRuntimeStore.updateOrchestration {
                it.copy(
                    lifecycleState = RuntimeLifecycleState.DEGRADED,
                    lastFailure = RuntimeFailure(
                        clazz = if (gate.reason.contains("failed")) {
                            RuntimeFailureClass.TRANSIENT
                        } else {
                            RuntimeFailureClass.POLICY_DENIED
                        },
                        reason = gate.reason,
                    ),
                    lastTransitionAtMs = nowMs(),
                )
            }
            telemetry.decision("start_gate", "allowed=false reason=${gate.reason}")
            RuntimeCommandResult(
                action = RuntimeActionType.DISPATCH_START,
                reason = command.reason,
                startGateDecision = mapped,
            )
        }
    }

    private fun handleStop(command: TrackerRuntimeCommands.Stop): RuntimeCommandResult {
        if (command.token != null && !correlation.isCurrent(command.token)) {
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "stale_token")
        }
        correlation.invalidate(TrackerRuntimeCommands.CORRELATION_RECORDING)
        TrackerRuntimeStore.updateOrchestration {
            it.copy(
                lifecycleState = RuntimeLifecycleState.IDLE,
                shouldBeRunning = false,
                lastIntentionalStop = true,
                lastTransitionAtMs = nowMs(),
            )
        }
        startGate.cancelRetry()
        watchdog.cancel()
        dispatchServiceAction(TrackingServiceIntents.ACTION_STOP)
        telemetry.decision("dispatch_stop", "reason=${command.reason}")
        return RuntimeCommandResult(action = RuntimeActionType.DISPATCH_STOP, reason = command.reason)
    }

    private fun handleRecover(reason: String): RuntimeCommandResult {
        val current = TrackerRuntimeStore.value.orchestration
        if (!current.shouldBeRunning) {
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "recovery_skipped_not_desired")
        }
        if (isServiceRunning()) {
            TrackerRuntimeStore.updateOrchestration {
                it.copy(lifecycleState = RuntimeLifecycleState.ACTIVE, lastTransitionAtMs = nowMs())
            }
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "recovery_skipped_service_running")
        }
        return handleStart(
            TrackerRuntimeCommands.Start(
                trigger = RuntimeTrigger.WATCHDOG_TICK,
                reason = "recovery:$reason",
            ),
        )
    }

    private fun handleReshow(reason: String): RuntimeCommandResult {
        dispatchServiceAction(TrackingServiceIntents.ACTION_RESHOW_FOREGROUND)
        return RuntimeCommandResult(action = RuntimeActionType.RESHOW_FOREGROUND, reason = reason)
    }

    private fun handleHeartbeat(timestampMs: Long): RuntimeCommandResult {
        TrackerRuntimeStore.updateOrchestration {
            it.copy(
                lastHeartbeatAtMs = timestampMs,
                lastTransitionAtMs = timestampMs,
                lifecycleState = if (it.shouldBeRunning || isServiceRunning()) {
                    RuntimeLifecycleState.ACTIVE
                } else {
                    it.lifecycleState
                },
            )
        }
        RecoveryTelemetry.record(appContext, "markHeartbeat")
        return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "heartbeat")
    }

    private fun handleSwitch(command: TrackerRuntimeCommands.SwitchRecordingTarget): RuntimeCommandResult {
        if (!correlation.isCurrent(command.token)) {
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "stale_token")
        }
        if (!TrackerRuntimeStore.value.isRecording) {
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "not_recording")
        }
        val stop = handleStop(
            TrackerRuntimeCommands.Stop(
                reason = "selected_tracker_restart_stop",
                token = command.token,
            ),
        )
        val startToken = correlation.next(TrackerRuntimeCommands.CORRELATION_RECORDING)
        handleStart(
            TrackerRuntimeCommands.Start(
                trigger = RuntimeTrigger.EXPLICIT_START,
                reason = "selected_tracker_restart_start",
                token = startToken,
            ),
        )
        return stop
    }

    private fun handleManualPoint(): RuntimeCommandResult {
        dispatchServiceAction(TrackingServiceIntents.ACTION_SEND_MANUAL_POINT)
        return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "manual_point")
    }

    private fun handleServiceStarted(command: TrackerRuntimeCommands.ServiceStarted): RuntimeCommandResult {
        TrackerRuntimeStore.updateOrchestration {
            it.copy(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true,
                lastIntentionalStop = false,
                lastStartTrigger = command.trigger,
                lastFailure = null,
                lastTransitionAtMs = command.timestampMs,
                lastHeartbeatAtMs = command.timestampMs,
            )
        }
        RecoveryTelemetry.record(appContext, "markTrackingStarted")
        scheduleWatchdog()
        return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "tracking_started")
    }

    private fun handleServiceStopped(reason: String, timestampMs: Long): RuntimeCommandResult {
        TrackerRuntimeStore.updateOrchestration {
            it.copy(
                lifecycleState = RuntimeLifecycleState.IDLE,
                shouldBeRunning = false,
                lastIntentionalStop = true,
                lastTransitionAtMs = timestampMs,
            )
        }
        RecoveryTelemetry.record(appContext, "markIntentionalStop reason=$reason")
        cancelWatchdog()
        return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = reason)
    }

    private fun handleStartupFailed(reason: String, timestampMs: Long): RuntimeCommandResult {
        return handleServiceStopped(reason, timestampMs)
    }

    private fun handleUnexpectedDestroy(command: TrackerRuntimeCommands.UnexpectedDestroy): RuntimeCommandResult {
        RecoveryTelemetry.record(appContext, "markUnexpectedDestroy wasTracking=${command.wasTracking}")
        if (!command.wasTracking) {
            return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "unexpected_destroy_idle")
        }
        TrackerRuntimeStore.updateOrchestration {
            it.copy(
                lifecycleState = RuntimeLifecycleState.RECOVERING,
                shouldBeRunning = true,
                lastIntentionalStop = false,
                lastTransitionAtMs = command.timestampMs,
            )
        }
        scheduleWatchdog()
        return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "unexpected_destroy")
    }

    private fun handleTaskRemoved(): RuntimeCommandResult {
        return RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "task_removed_noop")
    }

    private fun dispatchServiceAction(action: String) {
        appContext.startService(
            Intent(appContext, TrackingService::class.java).apply {
                this.action = action
                setPackage(appContext.packageName)
            },
        )
    }

    companion object {
        private const val TAG = "TrackerRuntimeEngine"

        @Volatile
        private var INSTANCE: TrackerRuntimeEngine? = null

        fun get(context: Context): TrackerRuntimeEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: create(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetInstance() {
            INSTANCE = null
        }

        fun resetForTests() {
            resetInstance()
            TrackerRuntimeStore.resetForTests()
        }

        internal fun createForTests(
            context: Context,
            startGate: GeoVaultForegroundStartGate,
            watchdog: GeoVaultRuntimeWatchdog,
            isServiceRunning: () -> Boolean,
            correlation: CommandCorrelation = CommandCorrelation(),
            nowMs: () -> Long = { System.currentTimeMillis() },
        ): TrackerRuntimeEngine {
            TrackerRuntimeStore.attach(context)
            return TrackerRuntimeEngine(
                context = context,
                startGate = startGate,
                watchdog = watchdog,
                telemetry = RuntimeTelemetry(context),
                isServiceRunning = isServiceRunning,
                correlation = correlation,
                nowMs = nowMs,
            )
        }

        private fun create(context: Context): TrackerRuntimeEngine {
            val appContext = context.applicationContext
            TrackerRuntimeStore.attach(appContext)
            val startGate = GeoVaultForegroundStartGate(
                context = appContext,
                config = GeoVaultForegroundStartGate.Config(
                    prefsName = "tracking_start_gate_v2",
                    retryRequestCode = 22001,
                    tag = "TrackingStartGate",
                ),
                startIntentFactory = {
                    Intent(appContext, TrackingService::class.java).apply {
                        action = TrackingServiceIntents.ACTION_START
                        setPackage(appContext.packageName)
                    }
                },
            )
            val watchdog = GeoVaultRuntimeWatchdog(
                context = appContext,
                config = GeoVaultRuntimeWatchdog.Config(
                    requestCode = 20101,
                    tag = "TrackingWatchdog",
                ),
                tickIntentFactory = {
                    Intent(appContext, TrackingRecoveryReceiver::class.java).apply {
                        action = RecoveryTelemetry.ACTION_RECOVERY_TICK
                        setPackage(appContext.packageName)
                    }
                },
            )
            GeoVaultCaptureLog.i(TAG, "created")
            return TrackerRuntimeEngine(
                context = appContext,
                startGate = startGate,
                watchdog = watchdog,
                telemetry = RuntimeTelemetry(appContext),
                isServiceRunning = { TrackingServiceLifecycleGate.isServiceStartingOrUsable() },
                correlation = CommandCorrelation(),
            )
        }
    }
}
