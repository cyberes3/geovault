package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapSessionWindowPolicyTest {

    @Test
    fun decide_nonSessionWindow_passesPointThrough() {
        val decision = TrackerMapSessionWindowPolicy.decide(
            recentDataWindow = "all",
            currentSessionStartMs = 1000L,
            incomingPropsJson = """{"starttimestamp":2000}"""
        )
        assertFalse(decision.shouldIgnorePoint)
        assertFalse(decision.shouldResetTrackGeometry)
        assertEquals(1000L, decision.nextSessionStartMs)
    }

    @Test
    fun decide_firstSessionPoint_setsSessionStart() {
        val decision = TrackerMapSessionWindowPolicy.decide(
            recentDataWindow = "session",
            currentSessionStartMs = null,
            incomingPropsJson = """{"starttimestamp":1710000000000}"""
        )
        assertFalse(decision.shouldIgnorePoint)
        assertFalse(decision.shouldResetTrackGeometry)
        assertEquals(1710000000000L, decision.nextSessionStartMs)
    }

    @Test
    fun decide_newerSession_resetsGeometryAndAcceptsPoint() {
        val decision = TrackerMapSessionWindowPolicy.decide(
            recentDataWindow = "current_session",
            currentSessionStartMs = 1710000000000L,
            incomingPropsJson = """{"starttimestamp":1710001000000}"""
        )
        assertTrue(decision.shouldResetTrackGeometry)
        assertFalse(decision.shouldIgnorePoint)
        assertEquals(1710001000000L, decision.nextSessionStartMs)
    }

    @Test
    fun decide_olderSession_ignoresPoint() {
        val decision = TrackerMapSessionWindowPolicy.decide(
            recentDataWindow = "session",
            currentSessionStartMs = 1710001000000L,
            incomingPropsJson = """{"starttimestamp":1710000000000}"""
        )
        assertFalse(decision.shouldResetTrackGeometry)
        assertTrue(decision.shouldIgnorePoint)
        assertEquals(1710001000000L, decision.nextSessionStartMs)
    }

    @Test
    fun resolveLatestSessionStartMs_normalizesSecondsAndPicksLatest() {
        val latest = TrackerMapSessionWindowPolicy.resolveLatestSessionStartMs(
            listOf(
                mapOf("starttimestamp" to 1_710_000_000),
                mapOf("starttimestamp" to 1_710_001_000_000L)
            )
        )
        assertEquals(1_710_001_000_000L, latest)
    }
}
