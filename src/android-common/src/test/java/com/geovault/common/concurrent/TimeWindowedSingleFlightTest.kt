package com.geovault.common.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TimeWindowedSingleFlightTest {

    @Test
    fun run_coalescesConcurrentCallsAndCachesWithinWindow() = runBlocking {
        var now = 1_000L
        val flight = TimeWindowedSingleFlight<String, Int>(
            scope = this,
            windowMs = 500L,
            nowMsProvider = { now },
        )
        val executeCount = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val jobs = List(4) {
            async {
                flight.run("resume") {
                    executeCount.incrementAndGet()
                    release.await()
                    9
                }
            }
        }
        delay(30L)
        release.complete(Unit)
        assertEquals(List(4) { 9 }, jobs.awaitAll())
        assertEquals(1, executeCount.get())

        now += 400L
        val cached = flight.run("resume") { executeCount.incrementAndGet(); 3 }
        assertEquals(9, cached)
        assertEquals(1, executeCount.get())

        now += 200L
        val refreshed = flight.run("resume") { executeCount.incrementAndGet(); 4 }
        assertEquals(4, refreshed)
        assertEquals(2, executeCount.get())
    }
}
