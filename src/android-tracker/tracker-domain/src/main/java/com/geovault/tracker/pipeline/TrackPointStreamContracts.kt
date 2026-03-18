package com.geovault.tracker.pipeline

import kotlinx.coroutines.flow.Flow

interface TrackPointEventPublisher {
    fun publish(event: TrackPointEvent)
}

interface TrackPointEventStream {
    val events: Flow<TrackPointEvent>
    val localGpsEvents: Flow<TrackPointEvent>
    val remoteStreamEvents: Flow<TrackPointEvent>
}
