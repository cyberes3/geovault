package com.geovault.tracker.policy

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class TrackPointBusDiagnostics(
    val isLocalDeliveryPaused: Boolean,
    val pausedBufferSize: Int,
    val deferredEmitCount: Long
)

object TrackPointBus {
    private const val REPLAY_EVENTS = 6144
    private const val EXTRA_BUFFER_EVENTS = 16384
    private const val PAUSED_BUFFER_CAPACITY = 512

    private val emitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val orderedEmitQueue = Channel<TrackPointEvent>(Channel.UNLIMITED)
    private val localDeliveryPaused = AtomicBoolean(false)
    private val deferredEmitCount = AtomicLong(0L)
    private val pausedLocalEvents = ArrayDeque<TrackPointEvent>()

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
        val sanitizedEvent = sanitize(event) ?: return
        if (sanitizedEvent.source == TrackPointSource.LOCAL_GPS && localDeliveryPaused.get()) {
            synchronized(pausedLocalEvents) {
                if (pausedLocalEvents.size >= PAUSED_BUFFER_CAPACITY) {
                    pausedLocalEvents.removeFirst()
                }
                pausedLocalEvents.addLast(sanitizedEvent)
            }
            return
        }
        val sendResult = orderedEmitQueue.trySend(sanitizedEvent)
        if (!sendResult.isSuccess) {
            deferredEmitCount.incrementAndGet()
        }
    }

    fun pauseLocalDelivery() {
        localDeliveryPaused.set(true)
    }

    fun resumeLocalDelivery() {
        if (!localDeliveryPaused.getAndSet(false)) return
        val buffered = synchronized(pausedLocalEvents) {
            val drained = pausedLocalEvents.toList()
            pausedLocalEvents.clear()
            drained
        }
        buffered.forEach {
            val sendResult = orderedEmitQueue.trySend(it)
            if (!sendResult.isSuccess) {
                deferredEmitCount.incrementAndGet()
            }
        }
    }

    fun deferredEmitEventsCount(): Long = deferredEmitCount.get()

    fun diagnostics(): TrackPointBusDiagnostics {
        val pausedSize = synchronized(pausedLocalEvents) { pausedLocalEvents.size }
        return TrackPointBusDiagnostics(
            isLocalDeliveryPaused = localDeliveryPaused.get(),
            pausedBufferSize = pausedSize,
            deferredEmitCount = deferredEmitCount.get()
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetForTests() {
        localDeliveryPaused.set(false)
        deferredEmitCount.set(0L)
        synchronized(pausedLocalEvents) {
            pausedLocalEvents.clear()
        }
        eventsFlow.resetReplayCache()
        while (orderedEmitQueue.tryReceive().isSuccess) {
            // Drain queue for deterministic tests.
        }
    }

    private fun sanitize(event: TrackPointEvent): TrackPointEvent? {
        if (!event.lat.isFinite() || !event.lon.isFinite()) return null
        if (event.lat !in -90.0..90.0 || event.lon !in -180.0..180.0) return null
        if (event.timestampMs <= 0L) return null
        if (event.orderingKey > 0L) return event
        return event.copy(orderingKey = event.timestampMs)
    }
}
