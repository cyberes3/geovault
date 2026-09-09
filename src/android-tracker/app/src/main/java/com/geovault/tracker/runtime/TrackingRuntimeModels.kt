package com.geovault.tracker.runtime

enum class RuntimeLifecycleState {
    IDLE,
    STARTING,
    ACTIVE,
    RECOVERING,
    DEGRADED,
    STOPPING,
    FAILED
}

enum class RuntimeTrigger {
    EXPLICIT_START,
    EXPLICIT_STOP,
    PROCESS_RESTART,
    BOOT,
    WATCHDOG_TICK,
    MAIN_START_ON_LAUNCH,
    RESHOW_FOREGROUND,
    TASK_REMOVED,
    UNKNOWN
}

enum class RuntimeFailureClass {
    NONE,
    TRANSIENT,
    PREREQUISITE,
    POLICY_DENIED,
    TERMINAL
}

data class RuntimeFailure(
    val clazz: RuntimeFailureClass,
    val reason: String
)

data class RuntimeState(
    val lifecycleState: RuntimeLifecycleState = RuntimeLifecycleState.IDLE,
    val shouldBeRunning: Boolean = false,
    val lastIntentionalStop: Boolean = false,
    val lastFailure: RuntimeFailure? = null,
    val lastStartTrigger: RuntimeTrigger? = null,
    val lastTransitionAtMs: Long = 0L,
    val lastHeartbeatAtMs: Long = 0L
)

enum class RuntimeActionType {
    DISPATCH_START,
    DISPATCH_STOP,
    RESHOW_FOREGROUND,
    NOOP
}
