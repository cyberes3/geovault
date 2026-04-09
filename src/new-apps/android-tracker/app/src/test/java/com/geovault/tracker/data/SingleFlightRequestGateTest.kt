package com.geovault.tracker.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightRequestGateTest {

    @Test
    fun run_coalescesConcurrentRequestsWithSameKey() = runBlocking {
        val gate = SingleFlightRequestGate<String, Int>()
        val executeCount = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val jobs = List(6) {
            async {
                gate.run("trackers") {
                    executeCount.incrementAndGet()
                    release.await()
                    7
                }
            }
        }
        delay(30L)
        release.complete(Unit)

        val values = jobs.awaitAll()
        assertEquals(1, executeCount.get())
        assertEquals(List(6) { 7 }, values)
    }
}
