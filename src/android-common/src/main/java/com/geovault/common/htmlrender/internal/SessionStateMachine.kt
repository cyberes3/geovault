package com.geovault.common.htmlrender.internal

/**
 * Guards the render lifecycle on the main thread. Illegal transitions throw [IllegalStateException].
 */
internal class SessionStateMachine {
    private var state: State = State.Idle

    enum class State {
        Idle,
        Loading,
        Ready,
        Outputting,
    }

    fun resetToIdle() {
        state = State.Idle
    }

    fun moveToLoading() {
        check(state == State.Idle) { "Expected Idle before Loading, was $state" }
        state = State.Loading
    }

    fun moveToReady() {
        check(state == State.Loading) { "Expected Loading before Ready, was $state" }
        state = State.Ready
    }

    fun moveToOutputting() {
        check(state == State.Ready) { "Expected Ready before Outputting, was $state" }
        state = State.Outputting
    }
}
