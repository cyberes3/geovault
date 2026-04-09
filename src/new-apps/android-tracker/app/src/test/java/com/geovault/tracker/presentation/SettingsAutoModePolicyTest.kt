package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAutoModePolicyTest {

    @Test
    fun shouldOverwriteUiField_allowsForcedOverwrite() {
        assertTrue(SettingsAutoModePolicy.shouldOverwriteUiField(hasFocus = true, force = true))
    }

    @Test
    fun shouldOverwriteUiField_blocksOverwriteWhenFocusedAndNotForced() {
        assertFalse(SettingsAutoModePolicy.shouldOverwriteUiField(hasFocus = true, force = false))
    }

    @Test
    fun shouldApplyManualDefaults_returnsFalseWhenAutoTrackingEnabled() {
        assertFalse(SettingsAutoModePolicy.shouldApplyManualDefaults(autoTrackingEnabled = true))
    }

    @Test
    fun shouldApplyManualDefaults_returnsTrueWhenAutoTrackingDisabled() {
        assertTrue(SettingsAutoModePolicy.shouldApplyManualDefaults(autoTrackingEnabled = false))
    }
}
