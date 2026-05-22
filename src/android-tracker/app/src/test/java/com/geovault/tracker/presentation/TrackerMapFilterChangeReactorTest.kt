package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.TrackerMapFilterChangeReactor.FilterChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapFilterChangeReactorTest {

    private fun tracker(id: String, window: String? = null): Tracker {
        val settings: Map<String, Any?>? = if (window != null) mapOf("recent_data_window" to window) else null
        return Tracker(id = id, name = id, color = null, settings = settings)
    }

    @Test
    fun `first observation of a tracker establishes baseline silently`() {
        val reactor = TrackerMapFilterChangeReactor()
        val result = reactor.observe(tracker("a", "1h"))
        assertEquals(FilterChange.None, result)
    }

    @Test
    fun `seeded baseline detects subsequent change as refresh`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h")))
        val result = reactor.observe(tracker("a", "session"))
        assertEquals(FilterChange.Refresh("a"), result)
    }

    @Test
    fun `seeded baseline returns None when window unchanged`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h")))
        val result = reactor.observe(tracker("a", "1h"))
        assertEquals(FilterChange.None, result)
    }

    @Test
    fun `consecutive observations after a change update the baseline`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h")))
        reactor.observe(tracker("a", "session"))
        val second = reactor.observe(tracker("a", "session"))
        assertEquals(FilterChange.None, second)
        val third = reactor.observe(tracker("a", "all"))
        assertEquals(FilterChange.Refresh("a"), third)
    }

    @Test
    fun `multiple trackers tracked independently`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h"), tracker("b", "all")))
        assertEquals(FilterChange.None, reactor.observe(tracker("a", "1h")))
        assertEquals(FilterChange.Refresh("b"), reactor.observe(tracker("b", "session")))
        assertEquals(FilterChange.None, reactor.observe(tracker("a", "1h")))
    }

    @Test
    fun `null to non-null transition is a refresh`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", window = null)))
        assertEquals(FilterChange.Refresh("a"), reactor.observe(tracker("a", "1h")))
    }

    @Test
    fun `non-null to null transition is a refresh`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h")))
        assertEquals(FilterChange.Refresh("a"), reactor.observe(tracker("a", window = null)))
    }

    @Test
    fun `blank tracker id is ignored`() {
        val reactor = TrackerMapFilterChangeReactor()
        val result = reactor.observe(tracker("", "1h"))
        assertEquals(FilterChange.None, result)
    }

    @Test
    fun `tracker id trimmed when matching`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h")))
        val result = reactor.observe(tracker("  a  ", "session"))
        assertTrue(result is FilterChange.Refresh && result.trackerId == "a")
    }

    @Test
    fun `tracker added post-seed observed first time returns None`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h")))
        assertEquals(FilterChange.None, reactor.observe(tracker("b", "session")))
        assertEquals(FilterChange.Refresh("b"), reactor.observe(tracker("b", "all")))
    }

    @Test
    fun `observeAll returns refreshes for changed seeded trackers`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h"), tracker("b", "all"), tracker("c", "session")))

        val changes = reactor.observeAll(
            listOf(
                tracker("a", "session"),
                tracker("b", "all"),
                tracker("c", window = null),
            )
        )

        assertEquals(listOf(FilterChange.Refresh("a"), FilterChange.Refresh("c")), changes)
        assertEquals(FilterChange.None, reactor.observe(tracker("a", "session")))
        assertEquals(FilterChange.None, reactor.observe(tracker("c", window = null)))
    }

    @Test
    fun `observeAll replaces baseline so removed trackers become first observations later`() {
        val reactor = TrackerMapFilterChangeReactor()
        reactor.seed(listOf(tracker("a", "1h"), tracker("removed", "1h")))

        val changes = reactor.observeAll(listOf(tracker("a", "1h")))

        assertEquals(emptyList<FilterChange.Refresh>(), changes)
        assertEquals(FilterChange.None, reactor.observe(tracker("removed", "session")))
    }
}
