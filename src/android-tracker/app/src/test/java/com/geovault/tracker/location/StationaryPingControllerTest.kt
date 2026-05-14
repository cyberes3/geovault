package com.geovault.tracker.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationaryPingControllerTest {

    @Test
    fun onPaused_requestsProbeAfterInterval() = runTest {
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS - 1L)
        runCurrent()
        assertTrue(actions.requests.isEmpty())

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf("interval_elapsed"), actions.requests)
    }

    @Test
    fun onPaused_repeatedWhileScheduled_keepsSingleTimer() = runTest {
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(1_000L)
        controller.onPaused(reason = "repeat_pause", providerAvailable = true)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()

        assertEquals(listOf("interval_elapsed"), actions.requests)
        assertTrue(actions.events.any { it.name == "stationary_ping_schedule_kept" })
    }

    @Test
    fun onResumed_cancelsScheduledProbe() = runTest {
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        controller.onResumed(reason = "significant_motion_resume")
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()

        assertTrue(actions.requests.isEmpty())
        assertTrue(actions.events.any { it.name == "stationary_ping_cancelled" })
    }

    @Test
    fun providerUnavailableAtDueTime_defersUntilProviderRestored() = runTest {
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = false)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()

        assertTrue(actions.requests.isEmpty())
        assertTrue(actions.events.any { it.name == "stationary_ping_deferred" })

        controller.onProviderRestored(reason = "provider_broadcast")

        assertEquals(listOf("provider_restored"), actions.requests)
    }

    @Test
    fun providerPausedBeforeDueTime_defersDueProbeUntilProviderRestored() = runTest {
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(10_000L)
        controller.onProviderPaused(reason = "provider_broadcast")
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()

        assertTrue(actions.requests.isEmpty())

        controller.onProviderRestored(reason = "provider_broadcast")

        assertEquals(listOf("provider_restored"), actions.requests)
    }

    @Test
    fun onPausedAfterDueProbe_schedulesNextCycle() = runTest {
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()
        controller.onPaused(reason = "paused_freshness_emitted", providerAvailable = true)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()

        assertEquals(listOf("interval_elapsed", "interval_elapsed"), actions.requests)
    }

    private fun TestScope.controller(actions: RecordingActions): StationaryPingController {
        return StationaryPingController(
            scope = this,
            actions = actions,
            clock = object : StationaryPingClock {
                override fun elapsedRealtimeMs(): Long = testScheduler.currentTime
            },
        )
    }

    private class RecordingActions : StationaryPingActions {
        val requests = mutableListOf<String>()
        val events = mutableListOf<Event>()

        override fun requestProbe(reason: String) {
            requests += reason
        }

        override fun logEvent(name: String, details: String) {
            events += Event(name, details)
        }
    }

    private data class Event(val name: String, val details: String)
}
