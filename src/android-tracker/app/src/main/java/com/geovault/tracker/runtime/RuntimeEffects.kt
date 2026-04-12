package com.geovault.tracker.runtime

interface RuntimeEffects {
    fun dispatchStart(trigger: RuntimeTrigger, reason: String): StartGateDecision

    fun dispatchStop()

    fun reshowForeground()

    fun scheduleWatchdog()

    fun cancelWatchdog()
}
