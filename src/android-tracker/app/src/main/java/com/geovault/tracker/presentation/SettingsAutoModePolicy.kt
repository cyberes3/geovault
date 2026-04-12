package com.geovault.tracker.presentation

internal object SettingsAutoModePolicy {
    fun shouldOverwriteUiField(hasFocus: Boolean, force: Boolean): Boolean {
        return force || !hasFocus
    }

    fun shouldApplyManualDefaults(autoTrackingEnabled: Boolean): Boolean {
        return !autoTrackingEnabled
    }
}
