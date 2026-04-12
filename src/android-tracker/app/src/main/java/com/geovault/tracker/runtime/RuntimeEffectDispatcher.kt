package com.geovault.tracker.runtime

import android.content.Context
import android.content.Intent
import com.geovault.tracker.TrackingService

class RuntimeEffectDispatcher(
    context: Context,
    private val scheduler: WatchdogScheduler,
    private val startGate: ServiceStartGate
) : RuntimeEffects {
    private val appContext = context.applicationContext

    override fun dispatchStart(trigger: RuntimeTrigger, reason: String): StartGateDecision {
        return startGate.dispatchStart(trigger, reason)
    }

    override fun dispatchStop() {
        appContext.startService(
            Intent(appContext, TrackingService::class.java).apply {
                action = TrackingService.ACTION_STOP
                setPackage(appContext.packageName)
            }
        )
    }

    override fun reshowForeground() {
        appContext.startService(
            Intent(appContext, TrackingService::class.java).apply {
                action = TrackingService.ACTION_RESHOW_FOREGROUND
                setPackage(appContext.packageName)
            }
        )
    }

    override fun scheduleWatchdog() {
        scheduler.schedule()
    }

    override fun cancelWatchdog() {
        scheduler.cancel()
    }
}
