package com.geovault.tracker.pipeline

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TrackPointBus {
    private const val REPLAY_EVENTS = 512
    private const val EXTRA_BUFFER_EVENTS = 2048

    private val eventsFlow = MutableSharedFlow<TrackPointEvent>(
        replay = REPLAY_EVENTS,
        extraBufferCapacity = EXTRA_BUFFER_EVENTS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<TrackPointEvent> = eventsFlow.asSharedFlow()

    fun publish(event: TrackPointEvent) {
        eventsFlow.tryEmit(event)
    }
}
