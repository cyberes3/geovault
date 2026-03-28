package com.geovault.tracker.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimeHealthPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val policy = RuntimeHealthPolicy(context)

    @Test
    fun reconcileState_resetsActiveState_whenServiceIsNotRunning() {
        val reconciled = policy.reconcileState(
            current = RuntimeState(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true,
                lastIntentionalStop = false
            ),
            isServiceRunning = false,
            reason = "test"
        )

        assertEquals(RuntimeLifecycleState.IDLE, reconciled.lifecycleState)
        assertFalse(reconciled.shouldBeRunning)
        assertFalse(reconciled.lastIntentionalStop)
        assertTrue(reconciled.lastFailure?.reason?.contains("service_not_running_state_reconciled") == true)
    }

    @Test
    fun reconcileState_promotesIdleToActive_whenServiceIsRunning() {
        val reconciled = policy.reconcileState(
            current = RuntimeState(
                lifecycleState = RuntimeLifecycleState.IDLE,
                shouldBeRunning = false
            ),
            isServiceRunning = true,
            reason = "test"
        )

        assertEquals(RuntimeLifecycleState.ACTIVE, reconciled.lifecycleState)
        assertTrue(reconciled.shouldBeRunning)
        assertFalse(reconciled.lastIntentionalStop)
    }
}
