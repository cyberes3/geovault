package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapTrailReloadReasonTest {

    @Test
    fun recentDataWindowChange_allowsServerAndMultiServerFetch() {
        val reason = TrackerMapTrailReloadReason.RecentDataWindowChange
        assertTrue(reason.allowServerHistoryFetch)
        assertTrue(reason.allowMultiServerHistoryFetch)
        assertEquals(2, reason.strength())
    }

    @Test
    fun mergedWith_recentDataWindowChangeWinsOverMetadata() {
        val merged = TrackerMapTrailReloadReason.MetadataMapRefresh.mergedWith(
            TrackerMapTrailReloadReason.RecentDataWindowChange,
        )
        assertEquals(TrackerMapTrailReloadReason.RecentDataWindowChange, merged)
    }
}
