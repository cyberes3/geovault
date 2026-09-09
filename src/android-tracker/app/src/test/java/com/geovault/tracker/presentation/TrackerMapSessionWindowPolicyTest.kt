package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

import com.geovault.tracker.map.MapTrailEngine
class TrackerMapSessionWindowPolicyTest {

    @Test
    fun normalizeTimestampToMs_secondsValue_convertsToMs() {
        val result = MapTrailEngine.normalizeTimestampToMs(1_710_000_000L)
        assertEquals(1_710_000_000_000L, result)
    }

    @Test
    fun normalizeTimestampToMs_msValue_returnsUnchanged() {
        val result = MapTrailEngine.normalizeTimestampToMs(1_710_000_000_000L)
        assertEquals(1_710_000_000_000L, result)
    }

    @Test
    fun normalizeTimestampToMs_stringValue_parsesAndNormalizes() {
        val result = MapTrailEngine.normalizeTimestampToMs("1710000000")
        assertEquals(1_710_000_000_000L, result)
    }

    @Test
    fun normalizeTimestampToMs_null_returnsNull() {
        val result = MapTrailEngine.normalizeTimestampToMs(null)
        assertNull(result)
    }

    @Test
    fun normalizeTimestampToMs_invalidString_returnsNull() {
        val result = MapTrailEngine.normalizeTimestampToMs("notanumber")
        assertNull(result)
    }
}
