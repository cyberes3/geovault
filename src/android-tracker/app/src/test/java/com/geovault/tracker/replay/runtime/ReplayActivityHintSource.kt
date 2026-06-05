package com.geovault.tracker.replay.runtime

import android.content.Context
import com.geovault.tracker.sensor.ActivityHint
import com.geovault.tracker.sensor.ActivityHintSource

/**
 * Controllable [ActivityHintSource] for end-to-end replay tests.
 *
 * [applyTransition] is called by [PositioningEndToEndReplayDriver] when a
 * [CaptureReplayActivityTransitionDto] event is processed in the merged timeline.
 * The hint is set active or cleared immediately, mirroring the real-time behaviour of
 * [com.geovault.tracker.aar.ActivityRecognitionHintBridge] without any expiry timer —
 * expiry is driven by [com.geovault.tracker.aar.ActivityRecognitionHintStore], which is
 * not used in replay. The replay system advances the clock and the positioning stack's
 * motion tick picks up the hint on the next [com.geovault.tracker.tracking.TrackingServiceConstants.AAR_SCRUTINY_WINDOW_MS] window.
 */
internal class ReplayActivityHintSource : ActivityHintSource {

    private var active: Boolean = false

    fun applyTransition(dto: CaptureReplayActivityTransitionDto) {
        active = dto.hintActive
    }

    override fun currentHint(nowMs: Long): ActivityHint? = if (active) ActivityHint else null

    override fun start(context: Context, trackId: String, trackingGeneration: Int) = Unit

    override fun stop() {
        active = false
    }
}
