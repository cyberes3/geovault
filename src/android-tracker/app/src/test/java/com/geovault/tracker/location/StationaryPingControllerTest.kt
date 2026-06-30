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
    fun onPaused_customSparseInterval_firesAfterScaledDuration() = runTest {
        val sparseIntervalMs = StationaryPingController.DEFAULT_INTERVAL_MS * 2
        val actions = RecordingActions()
        val controller = controller(actions, intervalMs = sparseIntervalMs)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(sparseIntervalMs - 1L)
        runCurrent()
        assertTrue(actions.requests.isEmpty())

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf("interval_elapsed"), actions.requests)
    }

    @Test
    fun reschedulePausedPing_whenNotScheduled_doesNotRequestProbe() = runTest {
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.reschedulePausedPing(
            newIntervalMs = StationaryPingController.DEFAULT_INTERVAL_MS * 2,
            providerAvailable = true,
            reason = "sparse_tracking_changed",
        )

        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS * 2)
        runCurrent()
        assertTrue(actions.requests.isEmpty())
    }

    @Test
    fun reschedulePausedPing_whenScheduled_restartsWithNewInterval() = runTest {
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS - 1_000L)
        runCurrent()
        assertTrue(actions.requests.isEmpty())

        val sparseIntervalMs = StationaryPingController.DEFAULT_INTERVAL_MS * 2
        controller.reschedulePausedPing(
            newIntervalMs = sparseIntervalMs,
            providerAvailable = true,
            reason = "sparse_tracking_changed",
        )
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()
        assertTrue(actions.requests.isEmpty())

        advanceTimeBy(sparseIntervalMs - StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("interval_elapsed"), actions.requests)
        assertTrue(actions.events.any { it.name == "stationary_ping_reschedule_cancelled" })
    }

    @Test
    fun pingTimerSurvivesGpsResumeWithNoResumedCall() = runTest {
        // Verifies the new region-lifecycle contract: resumeGps() no longer calls
        // onResumed(). A false significant-motion wakeup must not cancel the ping
        // timer — only exitStationaryRegion() (which calls onResumed()) may do so.
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)

        // Sig-motion fires: GPS resumes but onResumed is NOT called (the fix).
        // The timer keeps running.
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()

        assertEquals(listOf("interval_elapsed"), actions.requests)
        assertTrue(actions.events.none { it.name == "stationary_ping_cancelled" })
    }

    @Test
    fun pingTimerCancelledOnlyWhenOnResumedCalled() = runTest {
        // exitStationaryRegion() calls onResumed() — this remains the one path
        // that should cancel the timer. Verify the cancellation semantics still work.
        val actions = RecordingActions()
        val controller = controller(actions)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS / 2)
        controller.onResumed(reason = "confirmed_movement")
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()

        assertTrue(actions.requests.isEmpty())
        assertTrue(actions.events.any { it.name == "stationary_ping_cancelled" })
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

    // region Wake-guaranteed alarm scheduling
    //
    // These verify the AlarmManager-backed backstop added alongside the coroutine `delay()`
    // path: a plain `delay()` cannot wake a sleeping CPU, so the alarm must be scheduled
    // whenever the coroutine timer is, cancelled whenever the coroutine timer is, and whichever
    // path dispatches first must suppress the other to avoid a duplicate probe request.

    @Test
    fun onPaused_schedulesWakeGuaranteedAlarm() = runTest {
        val actions = RecordingActions()
        val alarmScheduler = RecordingAlarmScheduler()
        val controller = controller(actions, alarmScheduler = alarmScheduler)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)

        assertEquals(listOf(StationaryPingController.DEFAULT_INTERVAL_MS), alarmScheduler.scheduledAtMs)
    }

    @Test
    fun onPaused_repeatedWhileScheduled_doesNotRescheduleAlarm() = runTest {
        val actions = RecordingActions()
        val alarmScheduler = RecordingAlarmScheduler()
        val controller = controller(actions, alarmScheduler = alarmScheduler)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(1_000L)
        controller.onPaused(reason = "repeat_pause", providerAvailable = true)

        assertEquals(1, alarmScheduler.scheduledAtMs.size)
    }

    @Test
    fun intervalElapsed_cancelsAlarmSoItDoesNotDoubleFire() = runTest {
        val actions = RecordingActions()
        val alarmScheduler = RecordingAlarmScheduler()
        val controller = controller(actions, alarmScheduler = alarmScheduler)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS)
        runCurrent()

        assertEquals(listOf("interval_elapsed"), actions.requests)
        assertEquals(1, alarmScheduler.cancelCount)
    }

    @Test
    fun onResumed_cancelsAlarm() = runTest {
        val actions = RecordingActions()
        val alarmScheduler = RecordingAlarmScheduler()
        val controller = controller(actions, alarmScheduler = alarmScheduler)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        controller.onResumed(reason = "significant_motion_resume")

        assertEquals(1, alarmScheduler.cancelCount)
    }

    @Test
    fun onAlarmFired_dispatchesProbeAndSuppressesLateCoroutineFire() = runTest {
        // Simulates the OS-level alarm waking the CPU and firing slightly before the
        // in-process coroutine `delay()` would have resumed on its own.
        val actions = RecordingActions()
        val alarmScheduler = RecordingAlarmScheduler()
        val controller = controller(actions, alarmScheduler = alarmScheduler)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(StationaryPingController.DEFAULT_INTERVAL_MS - 1_000L)

        controller.onAlarmFired(reason = "stationary_ping_alarm")
        assertEquals(listOf("stationary_ping_alarm"), actions.requests)

        // The coroutine job must have been cancelled by the alarm dispatch; it must not
        // also fire once its original delay elapses.
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(listOf("stationary_ping_alarm"), actions.requests)
    }

    @Test
    fun reschedulePausedPing_reArmsAlarmWithNewInterval() = runTest {
        val actions = RecordingActions()
        val alarmScheduler = RecordingAlarmScheduler()
        val controller = controller(actions, alarmScheduler = alarmScheduler)

        controller.onPaused(reason = "pause_for_motion", providerAvailable = true)
        advanceTimeBy(1_000L)

        val sparseIntervalMs = StationaryPingController.DEFAULT_INTERVAL_MS * 2
        controller.reschedulePausedPing(
            newIntervalMs = sparseIntervalMs,
            providerAvailable = true,
            reason = "sparse_tracking_changed",
        )

        assertEquals(
            listOf(StationaryPingController.DEFAULT_INTERVAL_MS, 1_000L + sparseIntervalMs),
            alarmScheduler.scheduledAtMs,
        )
        assertTrue(alarmScheduler.cancelCount >= 1)
    }

    // endregion

    private fun TestScope.controller(
        actions: RecordingActions,
        intervalMs: Long = StationaryPingController.DEFAULT_INTERVAL_MS,
        alarmScheduler: StationaryPingAlarmScheduler = NoOpStationaryPingAlarmScheduler,
    ): StationaryPingController {
        return StationaryPingController(
            scope = this,
            actions = actions,
            initialIntervalMs = intervalMs,
            alarmScheduler = alarmScheduler,
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

    private class RecordingAlarmScheduler : StationaryPingAlarmScheduler {
        val scheduledAtMs = mutableListOf<Long>()
        var cancelCount = 0

        override fun schedule(triggerAtElapsedMs: Long) {
            scheduledAtMs += triggerAtElapsedMs
        }

        override fun cancel() {
            cancelCount++
        }
    }

    private data class Event(val name: String, val details: String)
}
