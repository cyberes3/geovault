package com.geovault.tracker.aar

import org.junit.Test

class ActivityRecognitionHintRecorderTest {

    private val recorder = ActivityRecognitionHintRecorder(
        trackId = "test-track-uuid",
        trackingGeneration = 1,
    )

    @Test
    fun `record does not throw for hint-active transition`() {
        recorder.record(
            wallMs = 1_000_000L,
            elapsedRealtimeNanos = 1_000_000_000_000L,
            eventTimeMs = 1_000L,
            activityLabel = "in_vehicle",
            transitionLabel = "enter",
            hintActive = true,
        )
    }

    @Test
    fun `record does not throw for clearing transition`() {
        recorder.record(
            wallMs = 1_000_000L,
            elapsedRealtimeNanos = 1_000_000_000_000L,
            eventTimeMs = 1_000L,
            activityLabel = "still",
            transitionLabel = "enter",
            hintActive = false,
        )
    }

    @Test
    fun `record does not throw for exit transition`() {
        recorder.record(
            wallMs = 1_000_000L,
            elapsedRealtimeNanos = 1_000_000_000_000L,
            eventTimeMs = 1_000L,
            activityLabel = "walking",
            transitionLabel = "exit",
            hintActive = false,
        )
    }
}
