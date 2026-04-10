package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapViewModelStreamingContractsTest {

    @Test
    fun resolveStreamTargetIds_singleSession_sameAsSelected_returnsEmpty() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtimeRunning = true,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-1",
            rosterTrackerIds = emptySet()
        )
        assertEquals(emptySet<String>(), ids)
    }

    @Test
    fun resolveStreamTargetIds_singleSession_differentFromSelected_returnsDisplayedOnly() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtimeRunning = true,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-2",
            rosterTrackerIds = emptySet()
        )
        assertEquals(setOf("tracker-2"), ids)
    }

    @Test
    fun resolveStreamTargetIds_groupPlaceholder_returnsEmpty() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtimeRunning = false,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-2",
            rosterTrackerIds = setOf("tracker-2", "tracker-3")
        )
        assertEquals(emptySet<String>(), ids)
    }

    @Test
    fun resolveStreamTargetIds_allQueue_whileRunning_excludesSelectedAndBlanks() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            runtimeRunning = true,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "",
            rosterTrackerIds = setOf("tracker-1", "tracker-2", " ", "tracker-3")
        )
        assertEquals(setOf("tracker-2", "tracker-3"), ids)
    }

    @Test
    fun resolveStreamTargetIds_allQueue_notRunning_keepsAllNormalized() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            runtimeRunning = false,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "",
            rosterTrackerIds = setOf("tracker-1", "tracker-2", " ")
        )
        assertEquals(setOf("tracker-1", "tracker-2"), ids)
    }

    @Test
    fun resolveHistoryClearRefreshAction_singleMode_otherTracker_noOp() {
        val action = TrackerMapViewModel.resolveHistoryClearRefreshAction(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "displayed",
            selectedTrackerId = "selected",
            clearedTrackerId = "other"
        )
        assertEquals(TrackerMapViewModel.HistoryClearRefreshAction.NO_OP, action)
    }

    @Test
    fun resolveHistoryClearRefreshAction_singleMode_displayedTracker_refreshes() {
        val action = TrackerMapViewModel.resolveHistoryClearRefreshAction(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "displayed",
            selectedTrackerId = "selected",
            clearedTrackerId = "displayed"
        )
        assertEquals(TrackerMapViewModel.HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE, action)
    }

    @Test
    fun resolveHistoryClearRefreshAction_groupMode_refreshesGroupOrAll() {
        val action = TrackerMapViewModel.resolveHistoryClearRefreshAction(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            displayedTrackerId = "",
            selectedTrackerId = "selected",
            clearedTrackerId = "any"
        )
        assertEquals(TrackerMapViewModel.HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL, action)
    }

    @Test
    fun resolveBottomCardVisibilityForMarkerTap_withSelection_showsCard() {
        val visible = TrackerMapViewModel.resolveBottomCardVisibilityForMarkerTap(
            hasSelectionCard = true
        )
        assertEquals(true, visible)
    }

    @Test
    fun resolveBackgroundTapShouldCloseBottomCard_hiddenAndNoSelection_noClose() {
        val shouldClose = TrackerMapViewModel.resolveBackgroundTapShouldCloseBottomCard(
            isBottomCardVisible = false,
            hasSelectionCard = false
        )
        assertEquals(false, shouldClose)
    }

    @Test
    fun resolveBackgroundTapShouldCloseBottomCard_visible_closes() {
        val shouldClose = TrackerMapViewModel.resolveBackgroundTapShouldCloseBottomCard(
            isBottomCardVisible = true,
            hasSelectionCard = true
        )
        assertEquals(true, shouldClose)
    }

    @Test
    fun resolveRenderSelectedMapTrackerId_hiddenCard_dropsSelectionHighlight() {
        val selectedId = TrackerMapViewModel.resolveRenderSelectedMapTrackerId(
            isBottomCardVisible = false,
            selectedMapTrackerId = "tracker-1"
        )
        assertEquals(null, selectedId)
    }

    @Test
    fun resolveRenderSelectedMapTrackerId_visibleCard_keepsSelectionHighlight() {
        val selectedId = TrackerMapViewModel.resolveRenderSelectedMapTrackerId(
            isBottomCardVisible = true,
            selectedMapTrackerId = "tracker-1"
        )
        assertEquals("tracker-1", selectedId)
    }

    @Test
    fun resolveFocusActionVisible_singleSession_hidesFocusAction() {
        val visible = TrackerMapViewModel.resolveFocusActionVisible(TrackerMapDisplayMode.SINGLE_SESSION)
        assertEquals(false, visible)
    }

    @Test
    fun resolveFocusActionVisible_allAndGroup_showFocusAction() {
        val allVisible = TrackerMapViewModel.resolveFocusActionVisible(TrackerMapDisplayMode.ALL_QUEUE)
        val groupVisible = TrackerMapViewModel.resolveFocusActionVisible(TrackerMapDisplayMode.GROUP_PLACEHOLDER)
        assertEquals(true, allVisible)
        assertEquals(true, groupVisible)
    }

    @Test
    fun resolveAllowedFallbackTrackerIds_singleSession_prefersDisplayedTrackerWhenVisible() {
        val allowed = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                selectedTrackerId = "tracker-1",
                displayedTrackerId = "tracker-2",
                visibleTrackerIds = setOf("tracker-1", "tracker-2")
            )
        )
        assertEquals(setOf("tracker-2"), allowed)
    }

    @Test
    fun resolveAllowedFallbackTrackerIds_allQueue_allVisibleTrackersAllowed() {
        val allowed = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = false,
                selectedTrackerId = "tracker-2",
                displayedTrackerId = "",
                visibleTrackerIds = setOf("tracker-1", "tracker-2", "tracker-3")
            )
        )
        assertEquals(setOf("tracker-1", "tracker-2", "tracker-3"), allowed)
    }

    @Test
    fun resolveAllowedFallbackTrackerIds_groupMode_allVisibleTrackersAllowed() {
        val allowed = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                runtimeRunning = true,
                selectedTrackerId = "tracker-2",
                displayedTrackerId = "",
                visibleTrackerIds = setOf("tracker-1")
            )
        )
        assertEquals(setOf("tracker-1"), allowed)
    }
}
