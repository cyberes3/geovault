package com.geovault.tracker.pipeline

import kotlinx.coroutines.flow.Flow

object TrackPointBusGateway : TrackPointEventPublisher, TrackPointEventStream {
    override val events: Flow<TrackPointEvent> = TrackPointBus.events
    override val localGpsEvents: Flow<TrackPointEvent> = TrackPointBus.localGpsEvents
    override val remoteStreamEvents: Flow<TrackPointEvent> = TrackPointBus.remoteStreamEvents

    override fun publish(event: TrackPointEvent) {
        TrackPointBus.publish(event)
    }
}

