package com.geovault.tracker.fragments

import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerSettingsState
import com.geovault.tracker.settings.TrackerTrackingProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsViewModelTest {

    @Test
    fun updateCalls_writeThroughToRepository() {
        val fakeRepository = FakeTrackerSettingsRepository()
        val viewModel = SettingsViewModel(fakeRepository)

        viewModel.setAutoTrackingMode(true)
        viewModel.setLoggingIntervalSec(42L)
        viewModel.setTrackingProfile(TrackerTrackingProfile.DRIVING)
        viewModel.setAccuracyFilterMeters(123f)
        viewModel.setLowAccuracyFallbackEnabled(true)
        viewModel.setLowAccuracyFallbackTimeoutSec(77L)
        viewModel.setKeepScreenOnWhileViewingMap(false)

        val state = fakeRepository.getSettings()
        assertTrue(state.autoTrackingMode)
        assertEquals(42L, state.loggingIntervalSec)
        assertEquals(TrackerTrackingProfile.DRIVING, state.trackingProfile)
        assertEquals(123f, state.accuracyFilterMeters, 0.0001f)
        assertTrue(state.lowAccuracyFallbackEnabled)
        assertEquals(77L, state.lowAccuracyFallbackTimeoutSec)
        assertFalse(state.keepScreenOnWhileViewingMap)
        assertFalse(viewModel.uiState.value.settings.sendExtendedData)
    }

    @Test
    fun uiState_phaseTracksRepositoryLoadState() {
        val fakeRepository = FakeTrackerSettingsRepository(
            initialLoadState = TrackerSettingsLoadState.Loading
        )
        val viewModel = SettingsViewModel(fakeRepository)
        assertEquals(SettingsPhase.Syncing, viewModel.uiState.value.phase)

        fakeRepository.markReady()

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 2_000L) {
            if (viewModel.uiState.value.phase == SettingsPhase.Ready) break
            Thread.sleep(10L)
        }
        assertEquals(SettingsPhase.Ready, viewModel.uiState.value.phase)
    }

    private class FakeTrackerSettingsRepository(
        initialLoadState: TrackerSettingsLoadState = TrackerSettingsLoadState.Ready
    ) : TrackerSettingsRepository {
        private val stateFlow = MutableStateFlow(
            TrackerSettingsState(
                loadState = initialLoadState,
                settings = TrackerSettings(sendExtendedData = false),
                wasTrackingBeforeExit = false,
                schemaVersion = 2,
                revision = 1L
            )
        )

        override fun isReady(): Boolean = stateFlow.value.isReady
        override fun getState(): TrackerSettingsState = stateFlow.value
        override fun observeState(): Flow<TrackerSettingsState> = stateFlow.asStateFlow()
        override fun getSettings(): TrackerSettings = stateFlow.value.settings
        override fun observeSettings(): Flow<TrackerSettings> = stateFlow.asStateFlow().map { it.settings }
        override fun dumpDebugState(reason: String) = Unit

        override fun setSendExtendedData(enabled: Boolean) {
            mutate { it.copy(sendExtendedData = enabled) }
        }

        override fun setSignificantDataOnly(enabled: Boolean) {
            mutate { it.copy(significantDataOnly = enabled) }
        }

        override fun setAutoTrackingMode(enabled: Boolean) {
            mutate { it.copy(autoTrackingMode = enabled) }
        }

        override fun setTrackingProfile(profile: TrackerTrackingProfile) {
            mutate { it.copy(trackingProfile = profile) }
        }

        override fun setLoggingIntervalSec(value: Long) {
            mutate { it.copy(loggingIntervalSec = value) }
        }

        override fun setDistanceFilterMeters(value: Float) {
            mutate { it.copy(distanceFilterMeters = value) }
        }

        override fun setAccuracyFilterMeters(value: Float) {
            mutate { it.copy(accuracyFilterMeters = value) }
        }

        override fun setLowAccuracyFallbackEnabled(enabled: Boolean) {
            mutate { it.copy(lowAccuracyFallbackEnabled = enabled) }
        }

        override fun setLowAccuracyFallbackTimeoutSec(value: Long) {
            mutate { it.copy(lowAccuracyFallbackTimeoutSec = value) }
        }

        override fun setStartOnBoot(enabled: Boolean) {
            mutate { it.copy(startOnBoot = enabled) }
        }

        override fun setStartTrackingOnLaunch(enabled: Boolean) {
            mutate { it.copy(startTrackingOnLaunch = enabled) }
        }

        override fun setKeepScreenOnWhileViewingMap(enabled: Boolean) {
            mutate { it.copy(keepScreenOnWhileViewingMap = enabled) }
        }

        override fun wasTrackingBeforeExit(): Boolean = false
        override fun setWasTrackingBeforeExit(value: Boolean) = Unit
        override fun clearWasTrackingBeforeExit() = Unit

        private fun mutate(transform: (TrackerSettings) -> TrackerSettings) {
            val previous = stateFlow.value
            stateFlow.value = previous.copy(
                settings = transform(previous.settings),
                revision = previous.revision + 1L
            )
        }

        fun markReady() {
            val previous = stateFlow.value
            stateFlow.value = previous.copy(
                loadState = TrackerSettingsLoadState.Ready,
                revision = previous.revision + 1L
            )
        }
    }
}
