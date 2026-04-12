package com.geovault.tracker.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackingRuntimeFacadeInitTest {

    @Test
    fun init_reconcilesStaleRunningRuntimeWhenServiceNotRunning() {
        val accessor = CountingRuntimeStateAccessor(
            runtime = RuntimeState(
                lifecycleState = RuntimeLifecycleState.RECOVERING,
                shouldBeRunning = true
            ),
            serviceRunning = false
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val facade = TrackingRuntimeFacade(
            repository = accessor,
            telemetry = RuntimeTelemetry(context),
            effects = NoopRuntimeEffects(),
            healthPolicy = RuntimeHealthPolicy(context),
            stateMachine = RuntimeStateMachine()
        )

        assertEquals(1, accessor.updateCalls)
        assertFalse(facade.state.value.runtime.shouldBeRunning)
        assertEquals(RuntimeLifecycleState.IDLE, facade.state.value.runtime.lifecycleState)
    }
}

private class CountingRuntimeStateAccessor(
    private var runtime: RuntimeState,
    private val serviceRunning: Boolean
) : RuntimeStateAccessor {
    var updateCalls: Int = 0

    override fun readState(): RuntimeState = runtime

    override fun updateState(transform: (RuntimeState) -> RuntimeState): RuntimeState {
        updateCalls++
        runtime = transform(runtime)
        return runtime
    }

    override fun isServiceRunning(): Boolean = serviceRunning
}

private class NoopRuntimeEffects : RuntimeEffects {
    override fun dispatchStart(trigger: RuntimeTrigger, reason: String): StartGateDecision {
        return StartGateDecision(allowed = true, reason = "noop")
    }

    override fun dispatchStop() = Unit
    override fun reshowForeground() = Unit
    override fun scheduleWatchdog() = Unit
    override fun cancelWatchdog() = Unit
}
