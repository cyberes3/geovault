package com.geovault.common.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCorrelationTest {

    @Test
    fun next_makesPriorTokenStale() {
        val correlation = CommandCorrelation()
        val first = correlation.next()
        assertTrue(correlation.isCurrent(first))
        val second = correlation.next()
        assertFalse(correlation.isCurrent(first))
        assertTrue(correlation.isCurrent(second))
    }

    @Test
    fun keys_areIndependent() {
        val correlation = CommandCorrelation()
        val recording = correlation.next("recording")
        val catalog = correlation.next("catalog")
        correlation.invalidate("recording")
        assertFalse(correlation.isCurrent(recording))
        assertTrue(correlation.isCurrent(catalog))
    }

    @Test
    fun current_matchesLastNext() {
        val correlation = CommandCorrelation()
        val token = correlation.next("switch")
        assertEquals(token, correlation.current("switch"))
    }
}
