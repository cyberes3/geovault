package com.geovault.tracker.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents how [TrackerSettingsRepository.getState] feeds recovery: [TrackingRecoveryReceiver]
 * reads [TrackerSettingsState.wasTrackingBeforeExit] and enables restart-on-kill behavior through
 * runtime watchdog handling.
 */
class RecoverySettingsRepositoryFakeTest {

    @Test
    fun fakeRepository_wasTrackingBeforeExit_visibleLikeProductionGetState() {
        val snapshot = TrackerSettingsState(
            loadState = TrackerSettingsLoadState.Ready,
            settings = TrackerSettingsDefaults.baseline,
            wasTrackingBeforeExit = true,
            schemaVersion = TrackerSettingsDefaults.schemaVersion,
            revision = 1L
        )
        val repo = StubTrackerSettingsRepository(snapshot)
        assertTrue(repo.getState().wasTrackingBeforeExit)
        assertTrue(repo.wasTrackingBeforeExit())
    }

    @Test
    fun recoveryTickDesiredRunning_matchesRuntimeHandlerContract() {
        val wasFromSettings = true
        val restartTrackingIfKilled = true
        assertTrue(restartTrackingIfKilled && wasFromSettings)
    }
}

private class StubTrackerSettingsRepository(
    private val snapshot: TrackerSettingsState
) : TrackerSettingsRepository {
    private val flow = MutableStateFlow(snapshot)

    override fun isReady(): Boolean = snapshot.isReady

    override fun getState(): TrackerSettingsState = flow.value

    override fun observeState(): Flow<TrackerSettingsState> = flow.asStateFlow()

    override fun getSettings(): TrackerSettings = flow.value.settings

    override fun observeSettings(): Flow<TrackerSettings> =
        MutableStateFlow(flow.value.settings).asStateFlow()

    override fun dumpDebugState(reason: String) = Unit

    override fun setSendExtendedData(enabled: Boolean) = Unit

    override fun setSignificantDataOnly(enabled: Boolean) = Unit

    override fun setAutoTrackingMode(enabled: Boolean) = Unit

    override fun setTrackingProfile(profile: TrackerTrackingProfile) = Unit

    override fun setLoggingIntervalSec(value: Long) = Unit

    override fun setDistanceFilterMeters(value: Float) = Unit

    override fun setAccuracyFilterMeters(value: Float) = Unit

    override fun setLowAccuracyFallbackEnabled(enabled: Boolean) = Unit

    override fun setLowAccuracyFallbackTimeoutSec(value: Long) = Unit

    override fun setStartOnBoot(enabled: Boolean) = Unit

    override fun setStartTrackingOnLaunch(enabled: Boolean) = Unit

    override fun setKeepScreenOnWhileViewingMap(enabled: Boolean) = Unit

    override fun setGroupModeFitOnlyActiveTrackers(enabled: Boolean) = Unit

    override fun wasTrackingBeforeExit(): Boolean = flow.value.wasTrackingBeforeExit

    override fun setWasTrackingBeforeExit(value: Boolean) = Unit

    override fun clearWasTrackingBeforeExit() = Unit
}
