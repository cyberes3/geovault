package com.geovault.common.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uses bounded [advanceTimeBy] + [runCurrent] steps rather than `advanceUntilIdle()` throughout:
 * a supervised collector over a perpetually-failing flow schedules a new retry forever, so
 * `advanceUntilIdle()` would never see the scheduler go idle and would hang the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupervisedCollectorTest {

    @Test
    fun launchSupervisedCollector_restartsAfterCollectorThrows() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val source = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val received = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()

        scope.launchSupervisedCollector(
            tag = "test",
            flow = source,
            retryDelayMs = 100L,
            onError = { _, error -> errors.add(error) },
        ) { value ->
            if (value == 2) error("boom")
            received.add(value)
        }
        runCurrent()

        source.emit(1)
        runCurrent()
        source.emit(2)
        runCurrent()
        // Collector coroutine is restarting after the failure; advance past retryDelayMs.
        advanceTimeBy(150L)
        runCurrent()
        source.emit(3)
        runCurrent()

        assertEquals(listOf(1, 3), received)
        assertEquals(1, errors.size)
        scope.cancel()
    }

    @Test
    fun launchSupervisedCollector_stopsRestartingOnceScopeIsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val attempts = AtomicInteger(0)

        val job = scope.launchSupervisedCollector(
            tag = "test",
            flow = flow<Unit> {
                attempts.incrementAndGet()
                throw IllegalStateException("always fails")
            },
            retryDelayMs = 50L,
        ) { }

        // Bounded advance: this flow fails and reschedules forever, so only ever step the
        // virtual clock forward by a fixed amount, never "until idle".
        advanceTimeBy(220L)
        runCurrent()
        val attemptsBeforeCancel = attempts.get()
        assertTrue(attemptsBeforeCancel >= 2)

        job.cancel()
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(attemptsBeforeCancel, attempts.get())
        scope.cancel()
    }

    @Test
    fun launchSupervisedCollector_exitsWithoutRestartWhenFlowCompletesNormally() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val attempts = AtomicInteger(0)
        val received = mutableListOf<Int>()

        scope.launchSupervisedCollector(
            tag = "test",
            flow = flow {
                attempts.incrementAndGet()
                emit(1)
            },
            retryDelayMs = 10L,
        ) { value -> received.add(value) }

        advanceTimeBy(200L)
        runCurrent()

        assertEquals(listOf(1), received)
        assertEquals(1, attempts.get())
        scope.cancel()
    }
}
