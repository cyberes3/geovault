package com.geovault.tracker.runtime

import com.geovault.tracker.location.TrackingLifecycleState

enum class RuntimeEffectType {
    DISPATCH_START,
    DISPATCH_STOP,
    RESHOW_FOREGROUND,
    SCHEDULE_WATCHDOG,
    CANCEL_WATCHDOG,
    NOOP
}

data class RuntimeEffect(
    val type: RuntimeEffectType,
    val reason: String
)

enum class RuntimeServiceEventType {
    TRACKING_STARTED,
    TRACKING_STOPPED,
    HEARTBEAT,
    STARTUP_FAILED,
    UNEXPECTED_DESTROY
}

data class RuntimeServiceEvent(
    val type: RuntimeServiceEventType,
    val trigger: RuntimeTrigger = RuntimeTrigger.UNKNOWN,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class TrackingSessionState(
    val runtime: RuntimeState = RuntimeState(),
    val trackingLifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val trackingRunning: Boolean = false,
    val lastServiceEvent: RuntimeServiceEventType? = null,
    val lastServiceEventReason: String? = null,
    val lastServiceEventAtMs: Long = 0L
)

data class TrackingSessionUpdateResult(
    val state: TrackingSessionState,
    val effects: List<RuntimeEffect>,
    val commandResult: RuntimeCommandResult? = null
)
