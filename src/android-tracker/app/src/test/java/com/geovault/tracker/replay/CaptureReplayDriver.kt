package com.geovault.tracker.replay

import com.geovault.tracker.location.AutoTrackingMotionEngine

class CaptureReplayDriver(
    private val session: CaptureReplaySession,
    private val onWallAdvance: (fromWallMs: Long, toWallMs: Long) -> Unit = { _, _ -> },
    private val onFrame: (frame: CaptureReplayFrame, index: Int) -> Unit,
) {
    fun runReplay() {
        var previousWallMs: Long? = null
        session.frames.forEachIndexed { index, frame ->
            val wallMs = frame.wallNowMs(session)
            if (previousWallMs != null) {
                onWallAdvance(previousWallMs, wallMs)
            }
            onFrame(frame, index)
            previousWallMs = wallMs
        }
    }

    companion object {
        fun runWithMotionTicks(
            session: CaptureReplaySession,
            engine: AutoTrackingMotionEngine,
            onFrame: (CaptureReplayFrame) -> Unit,
        ) {
            CaptureReplayDriver(
                session = session,
                onWallAdvance = { fromWallMs, toWallMs ->
                    CaptureReplayTickInjector.injectBetween(
                        engine = engine,
                        fromWallMs = fromWallMs,
                        toWallMs = toWallMs,
                    )
                },
                onFrame = { frame, _ -> onFrame(frame) },
            ).runReplay()
        }
    }
}
