package com.geovault.tracker.fragments

import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerTrackingProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        val state = fakeRepository.getSettings()
        assertTrue(state.autoTrackingMode)
        assertEquals(42L, state.loggingIntervalSec)
        assertEquals(TrackerTrackingProfile.DRIVING, state.trackingProfile)
        assertEquals(123f, state.accuracyFilterMeters, 0.0001f)
        assertFalse(viewModel.uiState.value.settings.sendExtendedData)
    }

    private class FakeTrackerSettingsRepository : TrackerSettingsRepository {
        private val flow = MutableStateFlow(TrackerSettings(sendExtendedData = false))

        override fun getSettings(): TrackerSettings = flow.value
        override fun observeSettings(): Flow<TrackerSettings> = flow.asStateFlow()

        override fun setSendExtendedData(enabled: Boolean) {
            flow.value = flow.value.copy(sendExtendedData = enabled)
        }

        override fun setSignificantDataOnly(enabled: Boolean) {
            flow.value = flow.value.copy(significantDataOnly = enabled)
        }

        override fun setResetTrackingIfKilled(enabled: Boolean) {
            flow.value = flow.value.copy(resetTrackingIfKilled = enabled)
        }

        override fun setAutoTrackingMode(enabled: Boolean) {
            flow.value = flow.value.copy(autoTrackingMode = enabled)
        }

        override fun setTrackingProfile(profile: TrackerTrackingProfile) {
            flow.value = flow.value.copy(trackingProfile = profile)
        }

        override fun setLoggingIntervalSec(value: Long) {
            flow.value = flow.value.copy(loggingIntervalSec = value)
        }

        override fun setDistanceFilterMeters(value: Float) {
            flow.value = flow.value.copy(distanceFilterMeters = value)
        }

        override fun setAccuracyFilterMeters(value: Float) {
            flow.value = flow.value.copy(accuracyFilterMeters = value)
        }

        override fun setStartOnBoot(enabled: Boolean) {
            flow.value = flow.value.copy(startOnBoot = enabled)
        }

        override fun setStartTrackingOnLaunch(enabled: Boolean) {
            flow.value = flow.value.copy(startTrackingOnLaunch = enabled)
        }

        override fun wasTrackingBeforeExit(): Boolean = false
        override fun setWasTrackingBeforeExit(value: Boolean) = Unit
        override fun clearWasTrackingBeforeExit() = Unit
    }
}
