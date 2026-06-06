package com.geovault.tracker.runtime

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.SelectedTrackerPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeRecoveryInvariantsTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        SelectedTrackerPrefs.clearSelectedTracker(appContext)
    }

    @Test
    fun watchdogTick_restartDisabled_neverDesiredEvenIfWasTrackingBeforeExit() {
        val repo = FakeRuntimeStateAccessor(
            runtime = RuntimeState(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true
            ),
            serviceRunning = false
        )
        val effects = RecordingRuntimeEffects()
        val handler = RuntimeCommandHandler(
            repository = repo,
            stateMachine = RuntimeStateMachine(),
            healthPolicy = RuntimeHealthPolicy(appContext),
            effects = effects,
            telemetry = RuntimeTelemetry(appContext)
        )
        val result = handler.handleWatchdogTick(
            current = TrackingSessionState(runtime = repo.readState()),
            request = WatchdogRecoveryRequest(
                restartTrackingIfKilled = false,
                wasTrackingBeforeExit = true
            )
        )
        assertEquals(RuntimeActionType.NOOP, result.commandResult?.action)
        assertEquals("watchdog_disabled", result.commandResult?.reason)
        assertEquals(1, effects.cancelWatchdogCalls)
        assertEquals(0, effects.scheduleWatchdogCalls)
        assertFalse(result.state.runtime.shouldBeRunning)
    }

    @Test
    fun watchdogTick_wasNotTrackingBeforeExit_skipsRecoveryDesired() {
        val repo = FakeRuntimeStateAccessor(RuntimeState())
        val effects = RecordingRuntimeEffects()
        val handler = RuntimeCommandHandler(
            repository = repo,
            stateMachine = RuntimeStateMachine(),
            healthPolicy = RuntimeHealthPolicy(appContext),
            effects = effects,
            telemetry = RuntimeTelemetry(appContext)
        )
        val result = handler.handleWatchdogTick(
            current = TrackingSessionState(),
            request = WatchdogRecoveryRequest(
                restartTrackingIfKilled = true,
                wasTrackingBeforeExit = false
            )
        )
        assertEquals("watchdog_disabled", result.commandResult?.reason)
        assertEquals(1, effects.cancelWatchdogCalls)
    }

    @Test
    fun watchdogTick_restartEnabled_andWasTrackingBeforeExit_attemptsRecoveryStart() {
        enableRecoveryPrerequisites()
        val repo = FakeRuntimeStateAccessor(
            runtime = RuntimeState(
                lifecycleState = RuntimeLifecycleState.RECOVERING,
                shouldBeRunning = true,
                lastHeartbeatAtMs = 0L
            ),
            serviceRunning = false
        )
        val effects = RecordingRuntimeEffects(
            startDecision = StartGateDecision(allowed = true, reason = "watchdog_recover")
        )
        val handler = RuntimeCommandHandler(
            repository = repo,
            stateMachine = RuntimeStateMachine(),
            healthPolicy = RuntimeHealthPolicy(appContext),
            effects = effects,
            telemetry = RuntimeTelemetry(appContext)
        )
        val result = handler.handleWatchdogTick(
            current = TrackingSessionState(runtime = repo.readState()),
            request = WatchdogRecoveryRequest(
                restartTrackingIfKilled = true,
                wasTrackingBeforeExit = true
            )
        )
        assertEquals(RuntimeActionType.DISPATCH_START, result.commandResult?.action)
        assertEquals("recovery:heartbeat_stale", result.commandResult?.reason)
        assertEquals(1, effects.dispatchStartCalls)
        assertEquals(RuntimeTrigger.WATCHDOG_TICK, effects.lastStartTrigger)
        assertEquals(0, effects.cancelWatchdogCalls)
        assertTrue(effects.scheduleWatchdogCalls >= 1)
        assertTrue(result.state.runtime.shouldBeRunning)
    }

    @Test
    fun watchdogTick_restartEnabledFreshHeartbeatDoesNotDispatchRecoveryStart() {
        enableRecoveryPrerequisites()
        val repo = FakeRuntimeStateAccessor(
            runtime = RuntimeState(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true,
                lastHeartbeatAtMs = System.currentTimeMillis()
            ),
            serviceRunning = false
        )
        val effects = RecordingRuntimeEffects()
        val handler = RuntimeCommandHandler(
            repository = repo,
            stateMachine = RuntimeStateMachine(),
            healthPolicy = RuntimeHealthPolicy(appContext),
            effects = effects,
            telemetry = RuntimeTelemetry(appContext)
        )

        val result = handler.handleWatchdogTick(
            current = TrackingSessionState(runtime = repo.readState()),
            request = WatchdogRecoveryRequest(
                restartTrackingIfKilled = true,
                wasTrackingBeforeExit = true
            )
        )

        assertEquals(RuntimeActionType.NOOP, result.commandResult?.action)
        assertEquals("heartbeat_fresh", result.commandResult?.reason)
        assertEquals(0, effects.dispatchStartCalls)
        assertEquals(0, effects.cancelWatchdogCalls)
        assertTrue(effects.scheduleWatchdogCalls >= 1)
        assertTrue(result.state.runtime.shouldBeRunning)
    }

    @Test
    fun watchdogTick_restartDisabled_doesNotDisableRuntimeWhenServiceRunning() {
        val repo = FakeRuntimeStateAccessor(
            runtime = RuntimeState(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true
            ),
            serviceRunning = true
        )
        val effects = RecordingRuntimeEffects()
        val handler = RuntimeCommandHandler(
            repository = repo,
            stateMachine = RuntimeStateMachine(),
            healthPolicy = RuntimeHealthPolicy(appContext),
            effects = effects,
            telemetry = RuntimeTelemetry(appContext)
        )
        val result = handler.handleWatchdogTick(
            current = TrackingSessionState(runtime = repo.readState()),
            request = WatchdogRecoveryRequest(
                restartTrackingIfKilled = false,
                wasTrackingBeforeExit = true
            )
        )

        assertEquals(RuntimeActionType.NOOP, result.commandResult?.action)
        assertEquals("watchdog_disabled_service_running", result.commandResult?.reason)
        assertEquals(1, effects.cancelWatchdogCalls)
        assertTrue(result.state.runtime.shouldBeRunning)
        assertEquals(RuntimeLifecycleState.ACTIVE, result.state.runtime.lifecycleState)
    }

    @Test
    fun watchdogTick_restartDisabled_waitsForRestartWhenWasTrackingAndServiceDown() {
        val repo = FakeRuntimeStateAccessor(
            runtime = RuntimeState(
                lifecycleState = RuntimeLifecycleState.RECOVERING,
                shouldBeRunning = true
            ),
            serviceRunning = false
        )
        val effects = RecordingRuntimeEffects()
        val handler = RuntimeCommandHandler(
            repository = repo,
            stateMachine = RuntimeStateMachine(),
            healthPolicy = RuntimeHealthPolicy(appContext),
            effects = effects,
            telemetry = RuntimeTelemetry(appContext)
        )
        val result = handler.handleWatchdogTick(
            current = TrackingSessionState(runtime = repo.readState()),
            request = WatchdogRecoveryRequest(
                restartTrackingIfKilled = false,
                wasTrackingBeforeExit = true
            )
        )

        assertEquals(RuntimeActionType.NOOP, result.commandResult?.action)
        assertEquals("watchdog_disabled", result.commandResult?.reason)
        assertEquals(1, effects.cancelWatchdogCalls)
        assertEquals(0, effects.scheduleWatchdogCalls)
        assertFalse(result.state.runtime.shouldBeRunning)
        assertEquals(RuntimeLifecycleState.IDLE, result.state.runtime.lifecycleState)
    }

    @Test
    fun startCommand_startGateDenied_marksDegradedAndDoesNotScheduleWatchdog() {
        val repo = FakeRuntimeStateAccessor(
            runtime = RuntimeState(
                lifecycleState = RuntimeLifecycleState.IDLE,
                shouldBeRunning = false
            ),
            serviceRunning = false
        )
        val effects = RecordingRuntimeEffects(
            startDecision = StartGateDecision(allowed = false, retryInMs = 1500L, reason = "min_gap")
        )
        val handler = RuntimeCommandHandler(
            repository = repo,
            stateMachine = RuntimeStateMachine(),
            healthPolicy = RuntimeHealthPolicy(appContext),
            effects = effects,
            telemetry = RuntimeTelemetry(appContext)
        )
        val result = handler.handleCommand(
            current = TrackingSessionState(runtime = repo.readState()),
            command = RuntimeCommand(
                type = RuntimeCommandType.START,
                trigger = RuntimeTrigger.BOOT,
                reason = "test_boot"
            )
        )
        assertNotNull(result.commandResult?.startGateDecision)
        assertFalse(result.commandResult!!.startGateDecision!!.allowed)
        assertEquals(RuntimeLifecycleState.DEGRADED, result.state.runtime.lifecycleState)
        assertEquals(0, effects.scheduleWatchdogCalls)
    }

    @Test
    fun startCommand_startGateAllowed_schedulesWatchdog() {
        val repo = FakeRuntimeStateAccessor(
            runtime = RuntimeState(
                lifecycleState = RuntimeLifecycleState.IDLE,
                shouldBeRunning = false
            ),
            serviceRunning = false
        )
        val effects = RecordingRuntimeEffects(
            startDecision = StartGateDecision(allowed = true, reason = "ok")
        )
        val handler = RuntimeCommandHandler(
            repository = repo,
            stateMachine = RuntimeStateMachine(),
            healthPolicy = RuntimeHealthPolicy(appContext),
            effects = effects,
            telemetry = RuntimeTelemetry(appContext)
        )
        val result = handler.handleCommand(
            current = TrackingSessionState(runtime = repo.readState()),
            command = RuntimeCommand(
                type = RuntimeCommandType.START,
                trigger = RuntimeTrigger.BOOT,
                reason = "test_boot"
            )
        )
        assertTrue(result.commandResult!!.startGateDecision!!.allowed)
        assertTrue(result.effects.any { it.type == RuntimeEffectType.SCHEDULE_WATCHDOG })
    }

    private fun enableRecoveryPrerequisites() {
        Shadows.shadowOf(appContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        SelectedTrackerPrefs.setSelectedTracker(
            appContext,
            trackerId = "11111111-1111-1111-1111-111111111111",
            trackerName = "Test Tracker"
        )
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }
}

private class FakeRuntimeStateAccessor(
    private var runtime: RuntimeState,
    private var serviceRunning: Boolean = false
) : RuntimeStateAccessor {
    override fun readState(): RuntimeState = runtime

    override fun updateState(transform: (RuntimeState) -> RuntimeState): RuntimeState {
        runtime = transform(runtime)
        return runtime
    }

    override fun isServiceRunning(): Boolean = serviceRunning
}

private class RecordingRuntimeEffects(
    var startDecision: StartGateDecision = StartGateDecision(allowed = true, reason = "test_ok")
) : RuntimeEffects {
    var cancelWatchdogCalls = 0
    var scheduleWatchdogCalls = 0
    var scheduleWatchdogInCalls = 0
    var lastScheduleWatchdogInDelayMs: Long = -1L
    var dispatchStartCalls = 0
    var lastStartTrigger: RuntimeTrigger? = null

    override fun dispatchStart(trigger: RuntimeTrigger, reason: String): StartGateDecision {
        dispatchStartCalls++
        lastStartTrigger = trigger
        return startDecision
    }

    override fun dispatchStop() = Unit

    override fun reshowForeground() = Unit

    override fun scheduleWatchdog() {
        scheduleWatchdogCalls++
    }

    override fun scheduleWatchdogIn(delayMs: Long) {
        scheduleWatchdogInCalls++
        lastScheduleWatchdogInDelayMs = delayMs
    }

    override fun cancelWatchdog() {
        cancelWatchdogCalls++
    }
}
