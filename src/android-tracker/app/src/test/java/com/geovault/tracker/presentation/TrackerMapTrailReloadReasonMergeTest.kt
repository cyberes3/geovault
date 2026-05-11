package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the strongest-wins coalescing semantics of [mergedWith]: a pending in-flight reload
 * merges with newly-requested reasons so forced fetches are never downgraded by a later
 * render-only tick, and non-force requests upgrade cleanly when a server fetch is needed.
 */
class TrackerMapTrailReloadReasonMergeTest {

    @Test
    fun nullPending_takesAnyIncoming() {
        assertEquals(
            TrackerMapTrailReloadReason.GenericMapRefresh,
            (null as TrackerMapTrailReloadReason?).mergedWith(TrackerMapTrailReloadReason.GenericMapRefresh),
        )
        assertEquals(
            TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming,
            (null as TrackerMapTrailReloadReason?).mergedWith(TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming),
        )
    }

    @Test
    fun forcedPending_isNotDowngradedByNonForceIncoming() {
        val pending: TrackerMapTrailReloadReason? = TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming
        val merged = pending.mergedWith(TrackerMapTrailReloadReason.GenericMapRefresh)
        assertEquals(TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming, merged)
    }

    @Test
    fun nonForcePending_isUpgradedByForcedIncoming() {
        val pending: TrackerMapTrailReloadReason? = TrackerMapTrailReloadReason.GenericMapRefresh
        val merged = pending.mergedWith(TrackerMapTrailReloadReason.StreamingStart)
        assertEquals(TrackerMapTrailReloadReason.StreamingStart, merged)
    }

    @Test
    fun twoForcedRequests_keepFirst() {
        val pending: TrackerMapTrailReloadReason? = TrackerMapTrailReloadReason.MapContextChange
        val merged = pending.mergedWith(TrackerMapTrailReloadReason.ExplicitTrackerLoad)
        assertEquals(TrackerMapTrailReloadReason.MapContextChange, merged)
    }

    @Test
    fun renderRefreshes_bothNonForce_keepFirst() {
        val pending: TrackerMapTrailReloadReason? = TrackerMapTrailReloadReason.GenericMapRefresh
        val merged = pending.mergedWith(TrackerMapTrailReloadReason.MetadataMapRefresh)
        assertEquals(TrackerMapTrailReloadReason.GenericMapRefresh, merged)
    }
}
