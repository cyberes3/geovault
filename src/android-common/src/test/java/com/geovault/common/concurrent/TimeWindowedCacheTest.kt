package com.geovault.common.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeWindowedCacheTest {

    @Test
    fun get_returnsValueWithinWindow() {
        var now = 1_000L
        val cache = TimeWindowedCache<String, String>(windowMs = 500L, nowMsProvider = { now })

        cache.put("key", "value")
        now += 500L

        assertEquals("value", cache.get("key"))
    }

    @Test
    fun get_returnsNullOnceWindowExpires() {
        var now = 1_000L
        val cache = TimeWindowedCache<String, String>(windowMs = 500L, nowMsProvider = { now })

        cache.put("key", "value")
        now += 501L

        assertNull(cache.get("key"))
    }

    @Test
    fun get_returnsNullForMissingKey() {
        val cache = TimeWindowedCache<String, String>(windowMs = 500L)
        assertNull(cache.get("missing"))
    }

    @Test
    fun invalidate_removesOnlyTheGivenKey() {
        val cache = TimeWindowedCache<String, String>(windowMs = 500L)
        cache.put("a", "1")
        cache.put("b", "2")

        cache.invalidate("a")

        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
    }

    @Test
    fun invalidateWhere_removesMatchingKeysOnly() {
        val cache = TimeWindowedCache<String, String>(windowMs = 500L)
        cache.put("tracker:1", "1")
        cache.put("tracker:2", "2")
        cache.put("group:1", "g")

        cache.invalidateWhere { it.startsWith("tracker:") }

        assertNull(cache.get("tracker:1"))
        assertNull(cache.get("tracker:2"))
        assertEquals("g", cache.get("group:1"))
    }

    @Test
    fun clear_removesEverything() {
        val cache = TimeWindowedCache<String, String>(windowMs = 500L)
        cache.put("a", "1")
        cache.put("b", "2")

        cache.clear()

        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun put_overwritesExistingEntryAndResetsItsWindow() {
        var now = 1_000L
        val cache = TimeWindowedCache<String, String>(windowMs = 500L, nowMsProvider = { now })

        cache.put("key", "first")
        now += 400L
        cache.put("key", "second")
        now += 400L

        assertEquals("second", cache.get("key"))
    }
}
