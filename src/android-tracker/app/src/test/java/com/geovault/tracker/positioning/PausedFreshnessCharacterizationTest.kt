package com.geovault.tracker.positioning

import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryRegionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PausedFreshnessCharacterizationTest {

    @Test
    fun probeLifecycle_startAndClear() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = StationaryFreshnessCoordinator(
            store = StationaryRegionStore(RuntimeEnvironment.getApplication()),
            pingController = StationaryPingController(
                scope = scope,
                initialIntervalMs = 60_000L,
                actions = object : StationaryPingActions {
                    override fun requestProbe(reason: String) {}
                    override fun logEvent(name: String, details: String) {}
                },
            ),
            scope = scope,
            actions = object : StationaryFreshnessActions {
                override fun logEvent(name: String, details: String) {}
                override fun onProbeTimeout() {}
            },
        )
        coordinator.resetSession()
        assertFalse(coordinator.probeActive)

        coordinator.startProbe(nowMs = 1_000L, timeoutMs = 30_000L, details = "characterization")
        assertTrue(coordinator.probeActive)

        coordinator.clearProbe(reason = "characterization")
        assertFalse(coordinator.probeActive)
    }
}
