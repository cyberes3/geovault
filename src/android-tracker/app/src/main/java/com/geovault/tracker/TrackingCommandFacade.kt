package com.geovault.tracker

import android.content.Context
import com.geovault.tracker.runtime.RuntimeCommand
import com.geovault.tracker.runtime.RuntimeCommandType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController

/**
 * UI / app-layer entry point for deterministic runtime commands.
 */
object TrackingCommandFacade {
    fun requestStart(context: Context, trigger: RuntimeTrigger, reason: String) {
        TrackingRuntimeController.get(context.applicationContext).handle(
            RuntimeCommand(type = RuntimeCommandType.START, trigger = trigger, reason = reason)
        )
    }

    fun requestStop(context: Context, reason: String = "ui_stop") {
        TrackingRuntimeController.get(context.applicationContext).handle(
            RuntimeCommand(type = RuntimeCommandType.STOP, trigger = RuntimeTrigger.EXPLICIT_STOP, reason = reason)
        )
    }
}
