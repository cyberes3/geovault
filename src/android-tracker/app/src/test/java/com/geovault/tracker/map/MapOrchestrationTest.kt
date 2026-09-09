package com.geovault.tracker.map

import com.geovault.common.coroutines.launchSupervisedCollector
import com.geovault.tracker.presentation.TrackerMapCameraDirective
import com.geovault.tracker.presentation.TrackerMapCameraDirectiveInput
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import com.geovault.tracker.presentation.TrackerMapStreamingCommand
import com.geovault.tracker.presentation.TrackerMapStreamingDecisionInput
import com.geovault.tracker.presentation.TrackerMapTrailReloadReason
import com.geovault.tracker.presentation.TrackerMapTrailSeedInput
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

@OptIn(ExperimentalCoroutinesApi::class)
class MapOrchestrationTest {

    @Test
    fun mailboxConsumer_restartsAfterThrowAndDeliversLaterHistoryCleared() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val mailbox = Channel<MapSessionMailbox>(Channel.UNLIMITED)
        val delivered = mutableListOf<MapSessionMailbox>()

        scope.launchSupervisedCollector(
            tag = "session-mailbox",
            flow = mailbox.receiveAsFlow(),
            retryDelayMs = 100L,
        ) { event ->
            if (event is MapSessionMailbox.StreamChanged) {
                error("collector boom")
            }
            delivered += event
        }
        runCurrent()

        mailbox.trySend(MapSessionMailbox.RuntimeChanged)
        runCurrent()
        mailbox.trySend(MapSessionMailbox.StreamChanged)
        runCurrent()
        advanceTimeBy(150L)
        runCurrent()
        mailbox.trySend(MapSessionMailbox.HistoryCleared("tracker-1"))
        runCurrent()

        assertEquals(listOf(MapSessionMailbox.RuntimeChanged), delivered.take(1))
        assertTrue(delivered.contains(MapSessionMailbox.HistoryCleared("tracker-1")))
        scope.cancel()
        mailbox.close()
    }

    @Test
    fun staleReloadSeed_skipsCommitAndDisarmsFit() {
        val planned = MapTrailEngine.trailSeed(
            TrackerMapTrailSeedInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                activeTrackerId = "t1",
                rosterTrackerIds = listOf("t1"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet()),
            )
        )
        val current = MapTrailEngine.trailSeed(
            TrackerMapTrailSeedInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                activeTrackerId = "t2",
                rosterTrackerIds = listOf("t2"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet()),
            )
        )
        assertTrue(MapTrailEngine.skipReloadCommitForStaleSeed(plannedSeed = planned, currentSeed = current))

        val engine = MapTrailEngine()
        engine.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, generation = 0L)
        engine.abandonStaleReload(TrackerMapTrailReloadReason.ExplicitTrackerLoad)

        assertFalse(
            engine.consumeReloadFitIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = 0L,
            )
        )
    }

    @Test
    fun resumeThenRosterRefresh_sameStreamingCommand() {
        val input = TrackerMapStreamingDecisionInput(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            remoteSubscriptionIds = setOf("a", "b"),
            displayedTrackerId = "",
            displayedTrackerName = "",
        )
        val resume = MapSessionEngine.resolveStreamingCommand(input)
        val refresh = MapSessionEngine.resolveStreamingCommand(input)

        assertTrue(resume is TrackerMapStreamingCommand.Start)
        assertEquals(resume, refresh)
        assertEquals(setOf("a", "b"), (resume as TrackerMapStreamingCommand.Start).trackerIds)
    }

    @Test
    fun liveFitThenGenerationBumpThenReloadInstant_doesNotConsumeStaleArm() {
        val render = MapRenderEngine()
        val trail = MapTrailEngine()
        val bounds = LatLngBounds.Builder()
            .include(LatLng(25.0, -80.0))
            .include(LatLng(26.0, -79.0))
            .build()

        render.resolveFromLockState(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = true,
                bounds = bounds,
            )
        )
        val liveFit = render.cameraDirective.value
        assertTrue(liveFit is TrackerMapCameraDirective.FitBounds)
        assertEquals(TrackerMapFitTrailMode.Instant, (liveFit as TrackerMapCameraDirective.FitBounds).mode)
        assertEquals(TrackerMapCameraDirective.Reason.LiveActiveFit, liveFit.reason)

        trail.armReloadFit(TrackerMapTrailReloadReason.ExplicitTrackerLoad, render.cameraGeneration)
        val generationAtArm = render.cameraGeneration
        render.onUserGestureStarted()
        assertNotEquals(generationAtArm, render.cameraGeneration)

        assertFalse(
            trail.consumeReloadFitIfLanded(
                reason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
                hasData = true,
                anyLockActive = false,
                currentGeneration = render.cameraGeneration,
            )
        )

        render.requestExplicitFit(bounds, TrackerMapFitTrailMode.Instant)
        val reloadFit = render.cameraDirective.value
        assertTrue(reloadFit is TrackerMapCameraDirective.FitBounds)
        assertEquals(TrackerMapFitTrailMode.Instant, (reloadFit as TrackerMapCameraDirective.FitBounds).mode)
        assertEquals(TrackerMapCameraDirective.Reason.ExplicitFit, reloadFit.reason)
        assertNotEquals(liveFit.id, reloadFit.id)
    }
}
