package com.geovault.tracker.pipeline

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import java.util.concurrent.atomic.AtomicLong

object TrackPointBus {
    // Keep enough replay headroom for longer background periods before the map UI re-attaches.
    private const val REPLAY_EVENTS = 6144
    private const val EXTRA_BUFFER_EVENTS = 16384
    private val emitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deferredEmitCount = AtomicLong(0L)
    private val orderedEmitQueue = Channel<TrackPointEvent>(Channel.UNLIMITED)

    private val eventsFlow = MutableSharedFlow<TrackPointEvent>(
        replay = REPLAY_EVENTS,
        extraBufferCapacity = EXTRA_BUFFER_EVENTS,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    init {
        emitScope.launch {
            for (event in orderedEmitQueue) {
                eventsFlow.emit(event)
            }
        }
    }

    val events: SharedFlow<TrackPointEvent> = eventsFlow.asSharedFlow()
    val localGpsEvents: Flow<TrackPointEvent> = events.filter { it.source == TrackPointSource.LOCAL_GPS }
    val remoteStreamEvents: Flow<TrackPointEvent> = events.filter { it.source == TrackPointSource.REMOTE_STREAM }

    fun publish(event: TrackPointEvent) {
        val sanitizedEvent = if (event.orderingKey > 0L) event else UnifiedTrackPointIngress.sanitize(event) ?: return
        val sendResult = orderedEmitQueue.trySend(sanitizedEvent)
        if (!sendResult.isSuccess) {
            deferredEmitCount.incrementAndGet()
        }
    }

    fun publishLocal(event: TrackPointEvent) {
        publish(event.copy(source = TrackPointSource.LOCAL_GPS))
    }

    fun publishRemote(event: TrackPointEvent) {
        publish(event.copy(source = TrackPointSource.REMOTE_STREAM))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetForTests() {
        while (orderedEmitQueue.tryReceive().isSuccess) {
            // Drain queued emissions from prior tests.
        }
        eventsFlow.resetReplayCache()
        deferredEmitCount.set(0L)
        UnifiedTrackPointIngress.resetForTests()
    }

    fun deferredEmitEventsCount(): Long {
        return deferredEmitCount.get()
    }

    fun ingressStats(): IngressStats {
        return UnifiedTrackPointIngress.stats()
    }
}

