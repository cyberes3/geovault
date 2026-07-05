package com.geovault.common.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightGateTest {

    @Test
    fun run_coalescesConcurrentRequestsWithSameKey() = runBlocking {
        val gate = SingleFlightGate<String, Int>()
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

    @Test
    fun run_distinctKeysExecuteIndependently() = runBlocking {
        val gate = SingleFlightGate<String, Int>()
        val executeCount = AtomicInteger(0)

        val a = async { gate.run("a") { executeCount.incrementAndGet(); 1 } }
        val b = async { gate.run("b") { executeCount.incrementAndGet(); 2 } }

        assertEquals(1, a.await())
        assertEquals(2, b.await())
        assertEquals(2, executeCount.get())
    }

    @Test
    fun run_afterPriorCompletionStartsANewOperation() = runBlocking {
        val gate = SingleFlightGate<String, Int>()
        val executeCount = AtomicInteger(0)

        val first = gate.run("trackers") { executeCount.incrementAndGet() }
        val second = gate.run("trackers") { executeCount.incrementAndGet() }

        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(2, executeCount.get())
    }
}
