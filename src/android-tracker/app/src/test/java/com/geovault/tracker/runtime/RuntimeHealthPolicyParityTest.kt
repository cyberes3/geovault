package com.geovault.tracker.runtime

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.SelectedTrackerPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows

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
    fun reconcile_keepsStopping_whenServiceNotRunning() {
        val current = RuntimeState(
            lifecycleState = RuntimeLifecycleState.STOPPING,
            shouldBeRunning = true
        )
        val reconciled = policy.reconcileState(
            current = current,
            isServiceRunning = false,
            reason = "parity_test"
        )

        assertEquals(current.lifecycleState, reconciled.lifecycleState)
        assertEquals(current.shouldBeRunning, reconciled.shouldBeRunning)
    }

    @Test
    fun reconcile_keepsDegraded_whenServiceNotRunning() {
        val current = RuntimeState(
            lifecycleState = RuntimeLifecycleState.DEGRADED,
            shouldBeRunning = true
        )
        val reconciled = policy.reconcileState(
            current = current,
            isServiceRunning = false,
            reason = "parity_test"
        )

        assertEquals(current.lifecycleState, reconciled.lifecycleState)
        assertEquals(current.shouldBeRunning, reconciled.shouldBeRunning)
    }

    @Test
    fun evaluateRecoveryHealth_staleHeartbeatServiceAlive_doesNotDispatchRecovery() {
        enableRecoveryPrerequisites()
        val health = policy.evaluateRecoveryHealth(
            state = RuntimeState(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true,
                lastHeartbeatAtMs = 0L,
            ),
            isServiceRunning = true,
        )

        assertFalse(health.isHealthy)
        assertFalse(health.shouldRecover)
        assertEquals("heartbeat_stale_service_alive", health.reason)
    }

    private fun enableRecoveryPrerequisites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        SelectedTrackerPrefs.setSelectedTracker(
            context,
            trackerId = "11111111-1111-1111-1111-111111111111",
            trackerName = "Test Tracker",
        )
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }
}
