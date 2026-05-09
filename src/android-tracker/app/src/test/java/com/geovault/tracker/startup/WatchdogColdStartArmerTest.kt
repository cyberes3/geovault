package com.geovault.tracker.startup

import com.geovault.tracker.settings.TrackerSettingsDefaults
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsState
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchdogColdStartArmerTest {

    @Test
    fun decide_ready_andWasTracking_andServiceNotRunning_rearms() {
        val state = state(loadState = TrackerSettingsLoadState.Ready, wasTrackingBeforeExit = true)
        assertEquals(
            ColdStartArmDecision.Rearm,
            ColdStartArmPolicy.decide(state, isServiceRunning = false)
        )
    }

    @Test
    fun decide_ready_andWasTracking_andServiceRunning_skips() {
        val state = state(loadState = TrackerSettingsLoadState.Ready, wasTrackingBeforeExit = true)
        assertEquals(
            ColdStartArmDecision.SkipServiceAlreadyRunning,
            ColdStartArmPolicy.decide(state, isServiceRunning = true)
        )
    }

    @Test
    fun decide_ready_andNoPreviousSession_skips() {
        val state = state(loadState = TrackerSettingsLoadState.Ready, wasTrackingBeforeExit = false)
        assertEquals(
            ColdStartArmDecision.SkipNoPreviousSession,
            ColdStartArmPolicy.decide(state, isServiceRunning = false)
        )
    }

    @Test
    fun decide_loading_skips() {
        val state = state(loadState = TrackerSettingsLoadState.Loading, wasTrackingBeforeExit = true)
        assertEquals(
            ColdStartArmDecision.SkipNotReady,
            ColdStartArmPolicy.decide(state, isServiceRunning = false)
        )
    }

    @Test
    fun decide_error_skips() {
        val state = state(loadState = TrackerSettingsLoadState.Error, wasTrackingBeforeExit = true)
        assertEquals(
            ColdStartArmDecision.SkipNotReady,
            ColdStartArmPolicy.decide(state, isServiceRunning = false)
        )
    }

    private fun state(
        loadState: TrackerSettingsLoadState,
        wasTrackingBeforeExit: Boolean
    ): TrackerSettingsState {
        return TrackerSettingsState(
            loadState = loadState,
            settings = TrackerSettingsDefaults.baseline,
            wasTrackingBeforeExit = wasTrackingBeforeExit,
            schemaVersion = TrackerSettingsDefaults.schemaVersion,
            revision = 1L
        )
    }
}
