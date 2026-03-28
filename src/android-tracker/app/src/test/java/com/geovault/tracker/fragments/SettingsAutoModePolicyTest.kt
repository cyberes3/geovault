package com.geovault.tracker.fragments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsAutoModePolicyTest {

    @Test
    fun shouldOverwriteUiField_skipsFocusedField_withoutForce() {
        val overwrite = SettingsAutoModePolicy.shouldOverwriteUiField(
            hasFocus = true,
            force = false
        )

        assertFalse(overwrite)
    }

    @Test
    fun shouldOverwriteUiField_overwritesFocusedField_withForce() {
        val overwrite = SettingsAutoModePolicy.shouldOverwriteUiField(
            hasFocus = true,
            force = true
        )

        assertTrue(overwrite)
    }

    @Test
    fun shouldApplyManualDefaults_skipsManualDefaults_whenAutoModeEnabled() {
        val normalizeManualFields = SettingsAutoModePolicy.shouldApplyManualDefaults(
            autoTrackingEnabled = true
        )

        assertFalse(normalizeManualFields)
    }

    @Test
    fun shouldApplyManualDefaults_staysSkippedAcrossPauseResume_whenAutoModeEnabled() {
        val pausePass = SettingsAutoModePolicy.shouldApplyManualDefaults(
            autoTrackingEnabled = true
        )
        val resumePass = SettingsAutoModePolicy.shouldApplyManualDefaults(
            autoTrackingEnabled = true
        )

        assertFalse(pausePass)
        assertFalse(resumePass)
    }
}
