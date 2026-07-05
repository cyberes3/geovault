package com.geovault.tracker.policy

import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
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
    val deferredEmitCount: Long,
    val droppedPausedLocalEvents: Long,
    val droppedNotSubscribedEvents: Long = 0L,
    val droppedInvalidEvents: Long = 0L,
    val droppedLocalEchoEvents: Long = 0L,
    val droppedRemotePolicyEvents: Long = 0L,
    val droppedQueueOverflowEvents: Long = 0L,
)

object TrackPointBus {
    private const val TAG = "TrackPointBus"
    private const val REPLAY_EVENTS = 6144
    private const val EXTRA_BUFFER_EVENTS = 16384
    private const val PAUSED_BUFFER_CAPACITY = 512
    /**
     * Bounded ordering queue capacity. Sized to absorb realistic bursts (e.g. dozens of trackers
     * each pushing a few points per second) without permitting unbounded growth on a stuck
     * consumer. Once full, the oldest queued event is dropped via [BufferOverflow.DROP_OLDEST];
     * this is preferable to OOM and is logged via [droppedQueueOverflowEvents].
     */
    private const val ORDERED_QUEUE_CAPACITY = 4096
    private const val WARNING_INTERVAL_MS = 30_000L

    private val emitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val orderedEmitQueue = Channel<TrackPointEvent>(
        capacity = ORDERED_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val localDeliveryPaused = AtomicBoolean(false)
    private val deferredEmitCount = AtomicLong(0L)
    private val pausedLocalEvents = ArrayDeque<TrackPointEvent>()
    private val droppedPausedLocalEvents = AtomicLong(0L)
    private val lastEnqueueFailureWarningAtMs = AtomicLong(0L)
    private val lastPausedDropWarningAtMs = AtomicLong(0L)

    /**
     * [orderedEmitQueue] uses [BufferOverflow.DROP_OLDEST], so `trySend` reports success even
     * when it silently evicted an older, not-yet-consumed event to make room -- there is no
     * callback for that eviction. These two counters reconstruct it indirectly: since the
     * channel never holds more than [ORDERED_QUEUE_CAPACITY] events, `published - consumed`
     * exceeding that capacity is only possible if at least one event was evicted before this
     * single consumer coroutine ever saw it, letting [publish] detect and log the drop the
     * channel itself stays silent about.
     */
    private val publishedToQueueCount = AtomicLong(0L)
    private val consumedFromQueueCount = AtomicLong(0L)
    private val droppedQueueOverflowEvents = AtomicLong(0L)
    private val lastQueueOverflowWarningAtMs = AtomicLong(0L)

    private val eventsFlow = MutableSharedFlow<TrackPointEvent>(
        replay = REPLAY_EVENTS,
        extraBufferCapacity = EXTRA_BUFFER_EVENTS,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    init {
        emitScope.launch {
            for (event in orderedEmitQueue) {
                consumedFromQueueCount.incrementAndGet()
                eventsFlow.emit(event)
            }
        }
    }

    val events: SharedFlow<TrackPointEvent> = eventsFlow.asSharedFlow()
    val localGpsEvents: Flow<TrackPointEvent> = events.filter { it.source == TrackPointSource.LOCAL_GPS }
    val remoteStreamEvents: Flow<TrackPointEvent> = events.filter { it.source == TrackPointSource.REMOTE_STREAM }

    fun publish(event: TrackPointEvent) {
        val orderedEvent = event.withOrderingKey()
        if (orderedEvent.source == TrackPointSource.LOCAL_GPS && localDeliveryPaused.get()) {
            synchronized(pausedLocalEvents) {
                if (pausedLocalEvents.size >= PAUSED_BUFFER_CAPACITY) {
                    pausedLocalEvents.removeFirst()
                    val dropped = droppedPausedLocalEvents.incrementAndGet()
                    warnRateLimited(
                        lastPausedDropWarningAtMs,
                        "Dropped paused LOCAL_GPS event while delivery is paused; dropped=$dropped buffer=$PAUSED_BUFFER_CAPACITY"
                    )
                }
                pausedLocalEvents.addLast(orderedEvent)
                if (CaptureLogThrottle.shouldLogInterval("bus_buffer_local", 30_000L)) {
                    GeoVaultCaptureLog.d(
                        TAG,
                        "map_update bus_buffer_local track=${orderedEvent.trackId.trim()} " +
                            "ts=${orderedEvent.timestampMs} buffered=${pausedLocalEvents.size}"
                    )
                }
            }
            return
        }
        val sendResult = orderedEmitQueue.trySend(orderedEvent)
        if (sendResult.isSuccess) {
            GeoVaultCaptureLog.v(
                TAG,
                "map_update bus_enqueued source=${orderedEvent.source} track=${orderedEvent.trackId.trim()} " +
                    "ts=${orderedEvent.timestampMs} order=${orderedEvent.orderingKey}"
            )
            val outstanding = publishedToQueueCount.incrementAndGet() - consumedFromQueueCount.get()
            if (outstanding > ORDERED_QUEUE_CAPACITY) {
                val dropped = droppedQueueOverflowEvents.incrementAndGet()
                warnRateLimited(
                    lastQueueOverflowWarningAtMs,
                    "Dropped ${orderedEvent.source} event track=${orderedEvent.trackId.trim()} due to ordered " +
                        "queue overflow (DROP_OLDEST); detected=$dropped outstanding=$outstanding capacity=$ORDERED_QUEUE_CAPACITY"
                )
            }
        }
        if (!sendResult.isSuccess) {
            // With DROP_OLDEST this should not normally fire (the channel evicts internally), but
            // closed-channel or other failure modes still land here. Treat them like deferred
            // emits so we keep telemetry symmetric.
            recordEnqueueFailure(orderedEvent)
        }
    }

    fun pauseLocalDelivery() {
        localDeliveryPaused.set(true)
        GeoVaultCaptureLog.d(TAG, "map_update bus_pause_local")
    }

    fun resumeLocalDelivery() {
        if (!localDeliveryPaused.getAndSet(false)) return
        val buffered = synchronized(pausedLocalEvents) {
            val drained = pausedLocalEvents.toList()
            pausedLocalEvents.clear()
            drained
        }
        GeoVaultCaptureLog.d(TAG, "map_update bus_resume_local buffered=${buffered.size}")
        buffered.forEach {
            val sendResult = orderedEmitQueue.trySend(it)
            if (!sendResult.isSuccess) {
                recordEnqueueFailure(it)
            }
        }
    }

    fun deferredEmitEventsCount(): Long = deferredEmitCount.get()

    fun diagnostics(): TrackPointBusDiagnostics {
        val pausedSize = synchronized(pausedLocalEvents) { pausedLocalEvents.size }
        val admissionSnapshot = RemoteTrackPointAdmissionDiagnostics.snapshot()
        return TrackPointBusDiagnostics(
            isLocalDeliveryPaused = localDeliveryPaused.get(),
            pausedBufferSize = pausedSize,
            deferredEmitCount = deferredEmitCount.get(),
            droppedPausedLocalEvents = droppedPausedLocalEvents.get(),
            droppedNotSubscribedEvents = admissionSnapshot.rejectedCount(
                RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "not_subscribed"
            ),
            droppedInvalidEvents = admissionSnapshot.rejectedCount(
                RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "invalid_payload"
            ),
            droppedLocalEchoEvents = admissionSnapshot.totalRejected(RemoteTrackPointAdmissionStage.LOCAL_ECHO),
            droppedRemotePolicyEvents = admissionSnapshot.totalRejected(RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING),
            droppedQueueOverflowEvents = droppedQueueOverflowEvents.get(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetForTests() {
        localDeliveryPaused.set(false)
        deferredEmitCount.set(0L)
        droppedPausedLocalEvents.set(0L)
        publishedToQueueCount.set(0L)
        consumedFromQueueCount.set(0L)
        droppedQueueOverflowEvents.set(0L)
        RemoteTrackPointAdmissionPipeline.resetForTests()
        synchronized(pausedLocalEvents) {
            pausedLocalEvents.clear()
        }
        eventsFlow.resetReplayCache()
        while (orderedEmitQueue.tryReceive().isSuccess) {
            // Drain queue for deterministic tests.
        }
    }

    private fun TrackPointEvent.withOrderingKey(): TrackPointEvent {
        if (orderingKey > 0L) return this
        return copy(orderingKey = timestampMs)
    }

    private fun recordEnqueueFailure(event: TrackPointEvent) {
        val deferred = deferredEmitCount.incrementAndGet()
        warnRateLimited(
            lastEnqueueFailureWarningAtMs,
            "Failed to enqueue ${event.source} event track=${event.trackId.trim()} deferred=$deferred"
        )
    }

    private fun warnRateLimited(lastWarningAtMs: AtomicLong, message: String) {
        val nowMs = System.currentTimeMillis()
        val previous = lastWarningAtMs.get()
        if (nowMs - previous < WARNING_INTERVAL_MS) return
        if (lastWarningAtMs.compareAndSet(previous, nowMs)) {
            runCatching { GeoVaultCaptureLog.w(TAG, message) }
        }
    }
}
