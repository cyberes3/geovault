package com.geovault.tracker.presentation

import com.geovault.tracker.streaming.ConnectionPhase
import com.geovault.tracker.streaming.LiveStreamSubscriptionState
import com.geovault.tracker.streaming.StreamIntent
import com.geovault.tracker.streaming.StreamingOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

import com.geovault.tracker.map.MapSessionEngine
class TrackerMapStreamingStatusPolicyTest {

    private fun leasesFor(vararg trackerIds: String): Map<StreamingOwner, StreamIntent> {
        if (trackerIds.isEmpty()) return emptyMap()
        return mapOf(StreamingOwner.MAP to StreamIntent(trackerIds = trackerIds.toSet()))
    }

    @Test
    fun resolve_idleStopped_noTargets_returnsInactive() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(),
            mapLeaseIds = emptySet(),
        )

        assertEquals(TrackerMapStreamingStatus.INACTIVE, result.status)
        assertEquals(0, result.activeCount)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_starting_noActiveIds_returnsConnecting() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.STARTING,
                activeTargets = emptySet(),
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
        assertEquals(0, result.activeCount)
    }

    @Test
    fun resolve_starting_withActiveIds_neverConnectedThisProcess_returnsConnecting() {
        // COLD-START BOOTSTRAP: activeTargets is pre-populated from persisted state as soon as
        // the bootstrap lease seeds, well before any real connection attempt this process has
        // made. This must read as "Connecting" (a fresh first attempt), not "Reconnecting".
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.STARTING,
                activeTargets = setOf("t1"),
                hasConnectedThisProcess = false,
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_starting_withActiveIds_previouslyConnectedThisProcess_returnsReconnecting() {
        // A genuine restart: this process already had a RUNNING connection before, so a new
        // STARTING with a carried-over active count really is a reconnect.
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.STARTING,
                activeTargets = setOf("t1"),
                hasConnectedThisProcess = true,
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_reconnecting_alwaysReconnecting() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.RECONNECTING,
                activeTargets = emptySet(),
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
    }

    @Test
    fun resolve_running_singleTracker_returnsLive() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1"),
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(1, result.activeCount)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_running_multipleTrackers_returnsLiveWithCount() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1", "t2", "t3"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1", "t2", "t3"),
            ),
            mapLeaseIds = setOf("t1", "t2", "t3"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(3, result.activeCount)
    }

    @Test
    fun resolve_running_partialActiveIds_returnsReconnecting() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1", "t2"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1"),
            ),
            mapLeaseIds = setOf("t1", "t2"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_running_extraActiveIds_returnsReconnecting() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1", "t2"),
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(2, result.activeCount)
    }

    @Test
    fun resolve_running_noActiveIdsForDesiredTargets_returnsConnecting() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = emptySet(),
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
        assertEquals(0, result.activeCount)
    }

    @Test
    fun resolve_running_paramsOwnsExtraTracker_mapTargetLive_returnsLive() {
        // Params holds its own lease for a tracker the map doesn't display. `activeTargets`
        // legitimately includes it too -- this must still read as Live for the map's own
        // tracker, not Reconnecting, since comparing against the map's narrower
        // the map's MAP lease alone would otherwise misclassify this as a dropped connection.
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                leases = mapOf(
                    StreamingOwner.MAP to StreamIntent(trackerIds = setOf("t1")),
                    StreamingOwner.PARAMS to StreamIntent(trackerIds = setOf("t2")),
                ),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1", "t2"),
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(2, result.activeCount)
    }

    @Test
    fun resolve_failedTransient_returnsReconnectingWithReason() {
        // A retry is still pending within the backoff budget for FAILED_TRANSIENT by
        // construction (the retry-budget-exhausted path escalates to FAILED_PERMANENT instead),
        // so this must read as "Reconnecting", not a terminal "Failed".
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.FAILED_TRANSIENT,
                activeTargets = setOf("t1"),
                failureReason = "Auth expired",
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
        assertEquals("Auth expired", result.failureReason)
    }

    @Test
    fun resolve_failedPermanent_returnsFailed() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.FAILED_PERMANENT,
                activeTargets = emptySet(),
                failureReason = null,
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.FAILED, result.status)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_stopped_withTargets_returnsInactive() {
        val result = MapSessionEngine.resolveStreamingStatus(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.IDLE,
                activeTargets = emptySet(),
            ),
            mapLeaseIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.INACTIVE, result.status)
    }

    @Test
    fun resolve_defaultModel_hasCorrectDefaults() {
        val model = TrackerMapStreamingStatusUiModel()

        assertEquals(TrackerMapStreamingStatus.INACTIVE, model.status)
        assertEquals(0, model.activeCount)
        assertNull(model.failureReason)
    }
}
