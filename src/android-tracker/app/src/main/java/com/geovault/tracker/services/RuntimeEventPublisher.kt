package com.geovault.tracker.services

import android.content.Context
import com.geovault.tracker.runtime.RuntimeServiceEvent
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController

class RuntimeEventPublisher(private val appContext: Context) {
    fun publish(type: RuntimeServiceEventType, reason: String, trigger: RuntimeTrigger = RuntimeTrigger.UNKNOWN) {
        TrackingRuntimeController.get(appContext).recordServiceEvent(
            RuntimeServiceEvent(
                type = type,
                trigger = trigger,
                reason = reason
            )
        )
    }
}
