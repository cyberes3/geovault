package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.GpsRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPaceTest {

    @Test
    fun runningStates_mapToMoving() {
        val movingStates = listOf(
            GpsRuntimeState.RUNNING,
            GpsRuntimeState.LOCKING,
            GpsRuntimeState.FALLBACK_PENDING,
            GpsRuntimeState.WAITING_FOR_PROVIDER,
        )
        for (state in movingStates) {
            assertEquals(RecordingPace.Moving, RecordingPace.from(state))
        }
    }

    @Test
    fun pausedStates_mapToStationary() {
        val stationaryStates = listOf(
            GpsRuntimeState.PAUSED_FOR_MOTION,
            GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
            GpsRuntimeState.INACTIVE,
        )
        for (state in stationaryStates) {
            assertEquals(RecordingPace.Stationary, RecordingPace.from(state))
        }
    }

    @Test
    fun stationaryRegion_forcesStationaryRegardlessOfGpsState() {
        assertEquals(
            RecordingPace.Stationary,
            RecordingPace.from(GpsRuntimeState.RUNNING, stationaryRegionActive = true),
        )
    }
}
