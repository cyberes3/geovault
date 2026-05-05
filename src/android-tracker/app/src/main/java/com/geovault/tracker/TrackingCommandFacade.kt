package com.geovault.tracker

import android.content.Context
import com.geovault.tracker.runtime.RuntimeCommand
import com.geovault.tracker.runtime.RuntimeCommandType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import java.util.concurrent.atomic.AtomicLong

/**
 * UI / app-layer entry point for deterministic runtime commands.
 */
object TrackingCommandFacade {
    private const val SELECTED_TRACKER_RESTART_STOP_REASON = "selected_tracker_restart_stop"
    private val explicitStopGeneration = AtomicLong(0L)

    fun requestStart(context: Context, trigger: RuntimeTrigger, reason: String) {
        TrackingRuntimeController.get(context.applicationContext).handle(
            RuntimeCommand(type = RuntimeCommandType.START, trigger = trigger, reason = reason)
        )
    }

    fun requestStop(context: Context, reason: String = "ui_stop") {
        if (reason != SELECTED_TRACKER_RESTART_STOP_REASON) {
            explicitStopGeneration.incrementAndGet()
        }
        TrackingRuntimeController.get(context.applicationContext).handle(
            RuntimeCommand(type = RuntimeCommandType.STOP, trigger = RuntimeTrigger.EXPLICIT_STOP, reason = reason)
        )
    }

    fun stopGeneration(): Long = explicitStopGeneration.get()
}
