package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapSessionWindowPolicyTest {

    @Test
    fun normalizeTimestampToMs_secondsValue_convertsToMs() {
        val result = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(1_710_000_000L)
        assertEquals(1_710_000_000_000L, result)
    }

    @Test
    fun normalizeTimestampToMs_msValue_returnsUnchanged() {
        val result = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(1_710_000_000_000L)
        assertEquals(1_710_000_000_000L, result)
    }

    @Test
    fun normalizeTimestampToMs_stringValue_parsesAndNormalizes() {
        val result = TrackerMapSessionWindowPolicy.normalizeTimestampToMs("1710000000")
        assertEquals(1_710_000_000_000L, result)
    }

    @Test
    fun normalizeTimestampToMs_null_returnsNull() {
        val result = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(null)
        assertNull(result)
    }

    @Test
    fun normalizeTimestampToMs_invalidString_returnsNull() {
        val result = TrackerMapSessionWindowPolicy.normalizeTimestampToMs("notanumber")
        assertNull(result)
    }
}
