package com.geovault.tracker.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireTimestampNormalizerTest {

    @Test
    fun normalizeToMilliseconds_secondsValue_returnsMilliseconds() {
        assertEquals(1_700_000_000_000L, WireTimestampNormalizer.normalizeToMilliseconds(1_700_000_000L))
    }

    @Test
    fun normalizeToMilliseconds_millisecondsValue_returnsUnchanged() {
        assertEquals(1_700_000_000_000L, WireTimestampNormalizer.normalizeToMilliseconds(1_700_000_000_000L))
    }

    @Test
    fun normalizeToMilliseconds_blankOrNonPositive_returnsNull() {
        assertNull(WireTimestampNormalizer.normalizeToMilliseconds(" "))
        assertNull(WireTimestampNormalizer.normalizeToMilliseconds(0L))
        assertNull(WireTimestampNormalizer.normalizeToMilliseconds(-1L))
    }
}
