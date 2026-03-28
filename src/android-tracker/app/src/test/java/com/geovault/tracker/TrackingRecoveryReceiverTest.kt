package com.geovault.tracker

import com.geovault.tracker.settings.TrackerSettingsLoadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRecoveryReceiverTest {
    @Test
    fun shouldProcessSettingsState_onlyWhenReady() {
        assertFalse(TrackingRecoveryReceiver.shouldProcessSettingsState(TrackerSettingsLoadState.Loading))
        assertFalse(TrackingRecoveryReceiver.shouldProcessSettingsState(TrackerSettingsLoadState.Error))
        assertTrue(TrackingRecoveryReceiver.shouldProcessSettingsState(TrackerSettingsLoadState.Ready))
    }
}
