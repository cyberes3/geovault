package com.geovault.tracker.location

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.services.TrackingMotionMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StationaryFreshnessCoordinatorTest {
    @Test
    fun startProbe_timesOutThroughCoordinatorAndClearsProbeState() = runTest {
        val actions = RecordingActions()
        val coordinator = coordinator(actions)
        coordinator.enterRegion(anchor = anchor(), nowMs = 2_000L)

        coordinator.startProbe(nowMs = 3_000L, timeoutMs = 5_000L, details = "test")
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, actions.timeoutCount)
        assertFalse(coordinator.probeActive)
        assertEquals(0L, coordinator.probeStartedAtMs)
        assertTrue(actions.events.any { it.name == "paused_freshness_probe_timeout" })
    }

    @Test
    fun markFreshnessPointPersistedStoresDurableDueBase() = runTest {
        val actions = RecordingActions()
        val coordinator = coordinator(actions)
        coordinator.enterRegion(anchor = anchor(), nowMs = 2_000L)
        coordinator.startProbe(nowMs = 3_000L, timeoutMs = 90_000L, details = "test")

        coordinator.markFreshnessPointPersisted(nowMs = 10_000L)

        assertFalse(coordinator.probeActive)
        assertEquals(310_000L, coordinator.nextFreshnessDueAtMs(intervalMs = 300_000L))
    }

    @Test
    fun recordPoorAccuracyFixKeepsProbeAgeAndCountTogether() = runTest {
        val actions = RecordingActions()
        val coordinator = coordinator(actions)
        coordinator.enterRegion(anchor = anchor(), nowMs = 2_000L)
        coordinator.startProbe(nowMs = 3_000L, timeoutMs = 90_000L, details = "test")

        val state = coordinator.recordPoorAccuracyFix(nowMs = 8_000L)

        assertEquals(1, state.poorAccuracyFixes)
        assertEquals(5_000L, state.probeAgeMs)
        assertEquals(1, coordinator.poorAccuracyFixes)
    }

    private fun TestScope.coordinator(actions: RecordingActions): StationaryFreshnessCoordinator {
        val store = StationaryRegionStore(ApplicationProvider.getApplicationContext()).also { it.clear() }
        val pingController = StationaryPingController(
            scope = this,
            actions = object : StationaryPingActions {
                override fun requestProbe(reason: String) = Unit
                override fun logEvent(name: String, details: String) {
                    actions.logEvent(name, details)
                }
            },
            clock = object : StationaryPingClock {
                override fun elapsedRealtimeMs(): Long = testScheduler.currentTime
            },
        )
        return StationaryFreshnessCoordinator(
            store = store,
            pingController = pingController,
            scope = this,
            actions = actions,
        )
    }

    private fun anchor(): RecoveryAnchorState {
        return RecoveryAnchorState.fromLocation(
            trackerId = "tracker-1",
            sessionBoundaryId = 1_000L,
            location = Location("gps").apply {
                latitude = 45.0
                longitude = -122.0
                time = 1_000L
                elapsedRealtimeNanos = 1_000_000L
                accuracy = 10f
            },
            radiusMeters = 50f,
            source = "test",
            motionMode = TrackingMotionMode.WALKING,
        )
    }

    private class RecordingActions : StationaryFreshnessActions {
        val events = mutableListOf<Event>()
        var timeoutCount = 0

        override fun logEvent(name: String, details: String) {
            events += Event(name, details)
        }

        override fun onProbeTimeout() {
            timeoutCount++
        }
    }

    private data class Event(val name: String, val details: String)
}
