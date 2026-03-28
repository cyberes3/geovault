package com.geovault.tracker.runtime

import android.content.Context
import android.content.Intent
import com.geovault.tracker.TrackingService

class RuntimeEffectDispatcher(
    context: Context,
    private val scheduler: WatchdogScheduler,
    private val startGate: ServiceStartGate
) {
    private val appContext = context.applicationContext

    fun dispatchStart(trigger: RuntimeTrigger, reason: String): StartGateDecision {
        return startGate.dispatchStart(trigger, reason)
    }

    fun dispatchStop() {
        appContext.startService(
            Intent(appContext, TrackingService::class.java).apply {
                action = TrackingService.ACTION_STOP
                setPackage(appContext.packageName)
            }
        )
    }

    fun reshowForeground() {
        appContext.startService(
            Intent(appContext, TrackingService::class.java).apply {
                action = TrackingService.ACTION_RESHOW_FOREGROUND
                setPackage(appContext.packageName)
            }
        )
    }

    fun scheduleWatchdog() {
        scheduler.schedule()
    }

    fun cancelWatchdog() {
        scheduler.cancel()
    }
}
