package com.geovault.tracker.presentation

import com.geovault.tracker.streaming.ConnectionPhase
import com.geovault.tracker.streaming.LiveStreamSubscriptionState
import com.geovault.tracker.streaming.OwnerLease
import com.geovault.tracker.streaming.StreamingOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapStreamingStatusPolicyTest {

    private fun leasesFor(vararg trackerIds: String): Map<StreamingOwner, OwnerLease> {
        if (trackerIds.isEmpty()) return emptyMap()
        return mapOf(StreamingOwner.MAP to OwnerLease(trackerIds = trackerIds.toSet()))
    }

    @Test
    fun resolve_idleStopped_noTargets_returnsInactive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(),
            streamTargetIds = emptySet(),
        )

        assertEquals(TrackerMapStreamingStatus.INACTIVE, result.status)
        assertEquals(0, result.activeCount)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_starting_noActiveIds_returnsConnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.STARTING,
                activeTargets = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
        assertEquals(0, result.activeCount)
    }

    @Test
    fun resolve_starting_withActiveIds_neverConnectedThisProcess_returnsConnecting() {
        // COLD-START BOOTSTRAP: activeTargets is pre-populated from persisted state as soon as
        // the bootstrap lease seeds, well before any real connection attempt this process has
        // made. This must read as "Connecting" (a fresh first attempt), not "Reconnecting".
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.STARTING,
                activeTargets = setOf("t1"),
                hasConnectedThisProcess = false,
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_starting_withActiveIds_previouslyConnectedThisProcess_returnsReconnecting() {
        // A genuine restart: this process already had a RUNNING connection before, so a new
        // STARTING with a carried-over active count really is a reconnect.
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.STARTING,
                activeTargets = setOf("t1"),
                hasConnectedThisProcess = true,
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_reconnecting_alwaysReconnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.RECONNECTING,
                activeTargets = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
    }

    @Test
    fun resolve_running_singleTracker_returnsLive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1"),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(1, result.activeCount)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_running_multipleTrackers_returnsLiveWithCount() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1", "t2", "t3"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1", "t2", "t3"),
            ),
            streamTargetIds = setOf("t1", "t2", "t3"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(3, result.activeCount)
    }

    @Test
    fun resolve_running_partialActiveIds_returnsReconnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1", "t2"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1"),
            ),
            streamTargetIds = setOf("t1", "t2"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_running_extraActiveIds_returnsReconnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1", "t2"),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(2, result.activeCount)
    }

    @Test
    fun resolve_running_noActiveIdsForDesiredTargets_returnsConnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                leases = leasesFor("t1"),
                connection = ConnectionPhase.RUNNING,
                activeTargets = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
        assertEquals(0, result.activeCount)
    }

    @Test
    fun resolve_running_paramsOwnsExtraTracker_mapTargetLive_returnsLive() {
        // Params holds its own lease for a tracker the map doesn't display. `activeTargets`
        // legitimately includes it too -- this must still read as Live for the map's own
        // tracker, not Reconnecting, since comparing against the map's narrower
        // `streamTargetIds` alone would otherwise misclassify this as a dropped connection.
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                leases = mapOf(
                    StreamingOwner.MAP to OwnerLease(trackerIds = setOf("t1")),
                    StreamingOwner.PARAMS to OwnerLease(trackerIds = setOf("t2")),
                ),
                connection = ConnectionPhase.RUNNING,
                activeTargets = setOf("t1", "t2"),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(2, result.activeCount)
    }

    @Test
    fun resolve_failedTransient_returnsReconnectingWithReason() {
        // A retry is still pending within the backoff budget for FAILED_TRANSIENT by
        // construction (the retry-budget-exhausted path escalates to FAILED_PERMANENT instead),
        // so this must read as "Reconnecting", not a terminal "Failed".
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.FAILED_TRANSIENT,
                activeTargets = setOf("t1"),
                failureReason = "Auth expired",
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
        assertEquals("Auth expired", result.failureReason)
    }

    @Test
    fun resolve_failedPermanent_returnsFailed() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.FAILED_PERMANENT,
                activeTargets = emptySet(),
                failureReason = null,
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.FAILED, result.status)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_stopped_withTargets_returnsInactive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamSubscriptionState(
                connection = ConnectionPhase.IDLE,
                activeTargets = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
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
