package com.geovault.tracker.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentDataWindowOptionsTest {

    @Test
    fun resolveValueFromInput_acceptsLabelAndRawValue() {
        val labels = listOf("All", "1 minute", "1 hour", "1 day", "1 week", "1 month", "Last Session")
        val oneHourLabel = labels[2]

        assertEquals("1h", RecentDataWindowOptions.resolveValueFromInput(oneHourLabel, labels))
        assertEquals("1h", RecentDataWindowOptions.resolveValueFromInput("1h", labels))
    }

    @Test
    fun resolveValueFromInput_rejectsUnknownValue() {
        val labels = listOf("All", "1 minute", "1 hour", "1 day", "1 week", "1 month", "Last Session")
        assertNull(RecentDataWindowOptions.resolveValueFromInput("2h", labels))
    }

    @Test
    fun indexForValue_unknownDefaultsToAll() {
        assertEquals(0, RecentDataWindowOptions.indexForValue("unknown"))
        assertEquals(0, RecentDataWindowOptions.indexForValue(null))
    }
}
