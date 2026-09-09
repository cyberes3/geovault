package com.geovault.tracker.presentation

import com.geovault.common.concurrent.TimeWindowedSingleFlight
import com.geovault.tracker.map.MapSessionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSessionRequestDeduperTest {

    @Test
    fun `loadOnce returns cached value within the dedupe window`() = runBlocking {
        var now = 0L
        val flight = TimeWindowedSingleFlight<String, String>(
            scope = this,
            windowMs = 1_000L,
            nowMsProvider = { now },
        )
        var calls = 0
        val key = "single:geometry:a"
        val first = flight.run(key) { calls++; "v1" }
        now = 500
        val second = flight.run(key) { calls++; "v2" }
        assertEquals("v1", first)
        assertEquals("v1", second)
        assertEquals(1, calls)
    }

    @Test
    fun `loadOnce refetches once the window expires`() = runBlocking {
        var now = 0L
        val flight = TimeWindowedSingleFlight<String, String>(
            scope = this,
            windowMs = 1_000L,
            nowMsProvider = { now },
        )
        var calls = 0
        val key = "single:geometry:a"
        flight.run(key) { calls++; "v1" }
        now = 2_000
        val second = flight.run(key) { calls++; "v2" }
        assertEquals("v2", second)
        assertEquals(2, calls)
    }

    @Test
    fun `clear drops every entry`() = runBlocking {
        var now = 0L
        val flight = TimeWindowedSingleFlight<String, String>(
            scope = this,
            windowMs = 1_000L,
            nowMsProvider = { now },
        )
        flight.run("single:geometry:a") { "a" }
        flight.run("single:geometry:b") { "b" }
        flight.clear()
        var calls = 0
        flight.run("single:geometry:a") { calls++; "a2" }
        assertEquals(1, calls)
    }

    @Test
    fun `invalidate purges the single-tracker key`() = runBlocking {
        var now = 0L
        val flight = TimeWindowedSingleFlight<String, String>(
            scope = this,
            windowMs = 10_000L,
            nowMsProvider = { now },
        )
        flight.run("single:geometry:a") { "a-cached" }
        MapSessionEngine.invalidateSessionRequests(flight, "a")
        var refetched = false
        val result = flight.run("single:geometry:a") { refetched = true; "a-fresh" }
        assertTrue(refetched)
        assertEquals("a-fresh", result)
    }

    @Test
    fun `invalidate purges multi-tracker keys that contain the tracker`() = runBlocking {
        var now = 0L
        val flight = TimeWindowedSingleFlight<String, String>(
            scope = this,
            windowMs = 10_000L,
            nowMsProvider = { now },
        )
        flight.run("multi:geometry:a,b,c") { "multi-cached" }
        MapSessionEngine.invalidateSessionRequests(flight, "b")
        var refetched = false
        val result = flight.run("multi:geometry:a,b,c") { refetched = true; "multi-fresh" }
        assertTrue(refetched)
        assertEquals("multi-fresh", result)
    }

    @Test
    fun `invalidate leaves entries for unrelated trackers intact`() = runBlocking {
        var now = 0L
        val flight = TimeWindowedSingleFlight<String, String>(
            scope = this,
            windowMs = 10_000L,
            nowMsProvider = { now },
        )
        flight.run("single:geometry:a") { "a" }
        flight.run("single:geometry:b") { "b" }
        MapSessionEngine.invalidateSessionRequests(flight, "a")
        var bRefetched = false
        flight.run("single:geometry:b") { bRefetched = true; "b2" }
        assertFalse(bRefetched)
    }

    @Test
    fun `invalidate matches full id boundaries only`() = runBlocking {
        var now = 0L
        val flight = TimeWindowedSingleFlight<String, String>(
            scope = this,
            windowMs = 10_000L,
            nowMsProvider = { now },
        )
        flight.run("single:geometry:abc") { "abc" }
        MapSessionEngine.invalidateSessionRequests(flight, "ab")
        var refetched = false
        flight.run("single:geometry:abc") { refetched = true; "abc2" }
        assertFalse(refetched)
    }

    @Test
    fun `invalidate blank id is a no-op`() = runBlocking {
        var now = 0L
        val flight = TimeWindowedSingleFlight<String, String>(
            scope = this,
            windowMs = 10_000L,
            nowMsProvider = { now },
        )
        flight.run("single:geometry:a") { "a" }
        MapSessionEngine.invalidateSessionRequests(flight, "   ")
        var refetched = false
        flight.run("single:geometry:a") { refetched = true; "a2" }
        assertFalse(refetched)
    }
}
