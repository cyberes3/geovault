package com.geovault.tracker.presentation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsDefaults
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTrackerDispatchTest {

    @Test
    fun setSendExtendedData_delegatesToRepository() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val recording = RecordingTrackerSettingsRepository()
        val vm = SettingsViewModel(app, recording)
        vm.setSendExtendedData(false)
        assertEquals(listOf("setSendExtendedData(false)"), recording.calls)
    }

    @Test
    fun setSparseTracking_delegatesToRepository() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val recording = RecordingTrackerSettingsRepository()
        val vm = SettingsViewModel(app, recording)
        vm.setSparseTracking(true)
        assertEquals(listOf("setSparseTracking(true)"), recording.calls)
    }

    @Test
    fun setSparseTracking_disableAlsoDelegates() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val recording = RecordingTrackerSettingsRepository()
        val vm = SettingsViewModel(app, recording)
        vm.setSparseTracking(false)
        assertEquals(listOf("setSparseTracking(false)"), recording.calls)
    }

    @Test
    fun setLowAccuracyFallbackTimeoutSecFromInput_ignoresNonNumeric() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val recording = RecordingTrackerSettingsRepository()
        val vm = SettingsViewModel(app, recording)
        vm.setLowAccuracyFallbackTimeoutSecFromInput("abc")
        assertEquals(emptyList<String>(), recording.calls)
    }

    @Test
    fun setLowAccuracyFallbackTimeoutSecFromInput_parsesAndDispatches() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val recording = RecordingTrackerSettingsRepository()
        val vm = SettingsViewModel(app, recording)
        vm.setLowAccuracyFallbackTimeoutSecFromInput("33")
        assertEquals(listOf("setLowAccuracyFallbackTimeoutSec(33)"), recording.calls)
    }
}

private class RecordingTrackerSettingsRepository : TrackerSettingsRepository {
    val calls = mutableListOf<String>()
    private val flow = MutableStateFlow(
        TrackerSettingsState(
            loadState = TrackerSettingsLoadState.Ready,
            settings = TrackerSettings(),
            wasTrackingBeforeExit = false,
            schemaVersion = TrackerSettingsDefaults.schemaVersion,
            revision = 0L,
        )
    )

    override fun isReady(): Boolean = true

    override fun getState(): TrackerSettingsState = flow.value

    override fun observeState(): Flow<TrackerSettingsState> = flow.asStateFlow()

    override fun getSettings(): TrackerSettings = flow.value.settings

    override fun observeSettings(): Flow<TrackerSettings> =
        MutableStateFlow(flow.value.settings).asStateFlow()

    override fun dumpDebugState(reason: String) = Unit

    override fun setSendExtendedData(enabled: Boolean) {
        calls += "setSendExtendedData($enabled)"
    }

    override fun setSignificantDataOnly(enabled: Boolean) {
        calls += "setSignificantDataOnly($enabled)"
    }

    override fun setSparseTracking(enabled: Boolean) {
        calls += "setSparseTracking($enabled)"
    }

    override fun setLowAccuracyFallbackEnabled(enabled: Boolean) {
        calls += "setLowAccuracyFallbackEnabled($enabled)"
    }

    override fun setLowAccuracyFallbackTimeoutSec(value: Long) {
        calls += "setLowAccuracyFallbackTimeoutSec($value)"
    }

    override fun setStartOnBoot(enabled: Boolean) {
        calls += "setStartOnBoot($enabled)"
    }

    override fun setStartTrackingOnLaunch(enabled: Boolean) {
        calls += "setStartTrackingOnLaunch($enabled)"
    }

    override fun setKeepScreenOnWhileViewingMap(enabled: Boolean) {
        calls += "setKeepScreenOnWhileViewingMap($enabled)"
    }

    override fun setGroupModeFitOnlyActiveTrackers(enabled: Boolean) {
        calls += "setGroupModeFitOnlyActiveTrackers($enabled)"
    }

    override fun wasTrackingBeforeExit(): Boolean = false

    override fun setWasTrackingBeforeExit(value: Boolean) = Unit

    override fun clearWasTrackingBeforeExit() = Unit
}
