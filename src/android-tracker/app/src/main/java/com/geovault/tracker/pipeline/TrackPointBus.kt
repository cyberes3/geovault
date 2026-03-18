package com.geovault.tracker.pipeline

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

object TrackPointBus {
    // Keep replay and buffer large enough for short UI gaps; overflow drops oldest to protect memory.
    private const val REPLAY_EVENTS = 512
    private const val EXTRA_BUFFER_EVENTS = 2048

    private val eventsFlow = MutableSharedFlow<TrackPointEvent>(
        replay = REPLAY_EVENTS,
        extraBufferCapacity = EXTRA_BUFFER_EVENTS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<TrackPointEvent> = eventsFlow.asSharedFlow()
    val localGpsEvents: Flow<TrackPointEvent> = events.filter { it.source == TrackPointSource.LOCAL_GPS }
    val remoteStreamEvents: Flow<TrackPointEvent> = events.filter { it.source == TrackPointSource.REMOTE_STREAM }

    fun publish(event: TrackPointEvent) {
        eventsFlow.tryEmit(event)
    }

    fun publishLocal(event: TrackPointEvent) {
        publish(event.copy(source = TrackPointSource.LOCAL_GPS))
    }

    fun publishRemote(event: TrackPointEvent) {
        publish(event.copy(source = TrackPointSource.REMOTE_STREAM))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetForTests() {
        eventsFlow.resetReplayCache()
    }
}
