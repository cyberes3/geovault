package com.geovault.tracker.presentation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapSessionRequestDeduperTest {

    @Test
    fun `loadOnce returns cached value within the dedupe window`() = runBlocking {
        var now = 0L
        val deduper = TrackerMapSessionRequestDeduper(scope = this, dedupeWindowMs = 1_000L, nowMsProvider = { now })
        var calls = 0
        val key = "single:geometry:a"
        val first = deduper.loadOnce(key) { calls++; "v1" }
        now = 500
        val second = deduper.loadOnce(key) { calls++; "v2" }
        assertEquals("v1", first)
        assertEquals("v1", second)
        assertEquals(1, calls)
    }

    @Test
    fun `loadOnce refetches once the window expires`() = runBlocking {
        var now = 0L
        val deduper = TrackerMapSessionRequestDeduper(scope = this, dedupeWindowMs = 1_000L, nowMsProvider = { now })
        var calls = 0
        val key = "single:geometry:a"
        deduper.loadOnce(key) { calls++; "v1" }
        now = 2_000
        val second = deduper.loadOnce(key) { calls++; "v2" }
        assertEquals("v2", second)
        assertEquals(2, calls)
    }

    @Test
    fun `clear drops every entry`() = runBlocking {
        var now = 0L
        val deduper = TrackerMapSessionRequestDeduper(scope = this, dedupeWindowMs = 1_000L, nowMsProvider = { now })
        deduper.loadOnce("single:geometry:a") { "a" }
        deduper.loadOnce("single:geometry:b") { "b" }
        deduper.clear()
        var calls = 0
        deduper.loadOnce("single:geometry:a") { calls++; "a2" }
        assertEquals(1, calls)
    }

    @Test
    fun `invalidate purges the single-tracker key`() = runBlocking {
        var now = 0L
        val deduper = TrackerMapSessionRequestDeduper(scope = this, dedupeWindowMs = 10_000L, nowMsProvider = { now })
        deduper.loadOnce("single:geometry:a") { "a-cached" }
        deduper.invalidate("a")
        var refetched = false
        val result = deduper.loadOnce("single:geometry:a") { refetched = true; "a-fresh" }
        assertTrue(refetched)
        assertEquals("a-fresh", result)
    }

    @Test
    fun `invalidate purges multi-tracker keys that contain the tracker`() = runBlocking {
        var now = 0L
        val deduper = TrackerMapSessionRequestDeduper(scope = this, dedupeWindowMs = 10_000L, nowMsProvider = { now })
        deduper.loadOnce("multi:geometry:a,b,c") { "multi-cached" }
        deduper.invalidate("b")
        var refetched = false
        val result = deduper.loadOnce("multi:geometry:a,b,c") { refetched = true; "multi-fresh" }
        assertTrue(refetched)
        assertEquals("multi-fresh", result)
    }

    @Test
    fun `invalidate leaves entries for unrelated trackers intact`() = runBlocking {
        var now = 0L
        val deduper = TrackerMapSessionRequestDeduper(scope = this, dedupeWindowMs = 10_000L, nowMsProvider = { now })
        deduper.loadOnce("single:geometry:a") { "a" }
        deduper.loadOnce("single:geometry:b") { "b" }
        deduper.invalidate("a")
        var bRefetched = false
        deduper.loadOnce("single:geometry:b") { bRefetched = true; "b2" }
        assertFalse(bRefetched)
    }

    @Test
    fun `invalidate matches full id boundaries only`() = runBlocking {
        var now = 0L
        val deduper = TrackerMapSessionRequestDeduper(scope = this, dedupeWindowMs = 10_000L, nowMsProvider = { now })
        deduper.loadOnce("single:geometry:abc") { "abc" }
        deduper.invalidate("ab")
        var refetched = false
        deduper.loadOnce("single:geometry:abc") { refetched = true; "abc2" }
        assertFalse(refetched)
    }

    @Test
    fun `invalidate blank id is a no-op`() = runBlocking {
        var now = 0L
        val deduper = TrackerMapSessionRequestDeduper(scope = this, dedupeWindowMs = 10_000L, nowMsProvider = { now })
        deduper.loadOnce("single:geometry:a") { "a" }
        deduper.invalidate("   ")
        var refetched = false
        deduper.loadOnce("single:geometry:a") { refetched = true; "a2" }
        assertFalse(refetched)
    }
}
