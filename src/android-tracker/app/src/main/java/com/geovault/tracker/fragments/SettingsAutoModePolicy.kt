package com.geovault.tracker.fragments

internal object SettingsAutoModePolicy {
    fun shouldOverwriteUiField(hasFocus: Boolean, force: Boolean): Boolean {
        return force || !hasFocus
    }

    fun shouldApplyManualDefaults(autoTrackingEnabled: Boolean): Boolean {
        return !autoTrackingEnabled
    }
}
