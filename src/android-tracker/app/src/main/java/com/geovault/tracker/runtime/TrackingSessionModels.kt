package com.geovault.tracker.runtime

data class StartGateDecision(
    val allowed: Boolean,
    val retryInMs: Long = 0L,
    val reason: String,
)

data class RuntimeCommandResult(
    val action: RuntimeActionType,
    val reason: String,
    val startGateDecision: StartGateDecision? = null
)
