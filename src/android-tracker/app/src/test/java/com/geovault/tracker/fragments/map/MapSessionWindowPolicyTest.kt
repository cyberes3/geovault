package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSessionWindowPolicyTest {
    @Test
    fun decide_ignoresNonSessionWindows() {
        val decision = MapSessionWindowPolicy.decide(
            recentDataWindow = "1h",
            currentSessionStartMs = 1_700_000_000_000L,
            incomingPropsJson = """{"starttimestamp":1700000500}"""
        )
        assertFalse(decision.shouldResetTrackGeometry)
        assertFalse(decision.shouldIgnorePoint)
        assertEquals(1_700_000_000_000L, decision.nextSessionStartMs)
    }

    @Test
    fun decide_resetsGeometryForNewerSessionBoundary() {
        val decision = MapSessionWindowPolicy.decide(
            recentDataWindow = "current_session",
            currentSessionStartMs = 1_700_000_000_000L,
            incomingPropsJson = """{"starttimestamp":1700000600}"""
        )
        assertTrue("decision=$decision", decision.shouldResetTrackGeometry)
        assertFalse("decision=$decision", decision.shouldIgnorePoint)
        assertEquals("decision=$decision", 1_700_000_600_000L, decision.nextSessionStartMs)
    }

    @Test
    fun decide_newerSessionDoesNotResetWhenResetSuppressed() {
        val decision = MapSessionWindowPolicy.decide(
            recentDataWindow = "current_session",
            currentSessionStartMs = 1_700_000_000_000L,
            incomingPropsJson = """{"starttimestamp":1700000600}""",
            allowResetOnNewSession = false
        )
        assertFalse("decision=$decision", decision.shouldResetTrackGeometry)
        assertFalse("decision=$decision", decision.shouldIgnorePoint)
        assertEquals("decision=$decision", 1_700_000_600_000L, decision.nextSessionStartMs)
    }

    @Test
    fun decide_ignoresOlderSessionPointAfterBoundary() {
        val decision = MapSessionWindowPolicy.decide(
            recentDataWindow = "session",
            currentSessionStartMs = 1_700_000_600_000L,
            incomingPropsJson = """{"starttimestamp":1700000000}"""
        )
        assertFalse("decision=$decision", decision.shouldResetTrackGeometry)
        assertTrue("decision=$decision", decision.shouldIgnorePoint)
        assertEquals("decision=$decision", 1_700_000_600_000L, decision.nextSessionStartMs)
    }

    @Test
    fun resolveLatestSessionStartMs_handlesMixedUnitsAndInvalidValues() {
        val resolved = MapSessionWindowPolicy.resolveLatestSessionStartMs(
            pointParams = listOf(
                mapOf("starttimestamp" to 1_700_000_100L),
                mapOf("starttimestamp" to 1_700_000_600_000L),
                mapOf("starttimestamp" to "invalid")
            )
        )
        assertEquals(1_700_000_600_000L, resolved)
    }
}
