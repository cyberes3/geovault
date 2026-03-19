package com.geovault.tracker.pipeline

object UnifiedTrackPointIngress {
    fun sanitize(event: TrackPointEvent, nowMs: Long = System.currentTimeMillis()): TrackPointEvent? {
        return TrackPointPipeline.process(event = event, nowMs = nowMs).canonicalEvent
    }

    fun stats(): IngressStats {
        return TrackPointPipeline.stats()
    }

    fun resetForTests() {
        TrackPointPipeline.resetForTests()
    }
}

