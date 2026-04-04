package com.geovault.tracker.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeHealthPolicyParityTest {

    private lateinit var policy: RuntimeHealthPolicy

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        policy = RuntimeHealthPolicy(context)
    }

    @Test
    fun reconcile_promotesToActive_whenServiceRunningButRuntimeNotDesired() {
        val reconciled = policy.reconcileState(
            current = RuntimeState(
                lifecycleState = RuntimeLifecycleState.IDLE,
                shouldBeRunning = false,
                lastIntentionalStop = true
            ),
            isServiceRunning = true,
            reason = "parity_test"
        )

        assertEquals(RuntimeLifecycleState.ACTIVE, reconciled.lifecycleState)
        assertTrue(reconciled.shouldBeRunning)
        assertFalse(reconciled.lastIntentionalStop)
    }

    @Test
    fun reconcile_resetsRecovering_whenServiceNotRunning() {
        val reconciled = policy.reconcileState(
            current = RuntimeState(
                lifecycleState = RuntimeLifecycleState.RECOVERING,
                shouldBeRunning = true
            ),
            isServiceRunning = false,
            reason = "parity_test"
        )

        assertEquals(RuntimeLifecycleState.IDLE, reconciled.lifecycleState)
        assertFalse(reconciled.shouldBeRunning)
    }

    @Test
    fun reconcile_resetsStopping_whenServiceNotRunning() {
        val reconciled = policy.reconcileState(
            current = RuntimeState(
                lifecycleState = RuntimeLifecycleState.STOPPING,
                shouldBeRunning = true
            ),
            isServiceRunning = false,
            reason = "parity_test"
        )

        assertEquals(RuntimeLifecycleState.IDLE, reconciled.lifecycleState)
        assertFalse(reconciled.shouldBeRunning)
    }

    @Test
    fun reconcile_resetsDegraded_whenServiceNotRunning() {
        val reconciled = policy.reconcileState(
            current = RuntimeState(
                lifecycleState = RuntimeLifecycleState.DEGRADED,
                shouldBeRunning = true
            ),
            isServiceRunning = false,
            reason = "parity_test"
        )

        assertEquals(RuntimeLifecycleState.IDLE, reconciled.lifecycleState)
        assertFalse(reconciled.shouldBeRunning)
    }
}
