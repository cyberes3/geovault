package com.geovault.tracker

object AppForegroundState {
    @Volatile
    private var foreground: Boolean = false

    fun markForeground() {
        foreground = true
    }

    fun markBackground() {
        foreground = false
    }

    fun isForeground(): Boolean = foreground
}
