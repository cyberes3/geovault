package com.geovault.tracker.sensor

/**
 * Connects a [SignificantMotionTrigger] to a resume callback. Used by [com.geovault.tracker.TrackingService]
 * to request the trigger when GPS is paused and run [onResume] when significant motion is detected.
 */
class SignificantMotionResumeBridge(
    private val trigger: SignificantMotionTrigger,
    private val onResume: () -> Unit
) {
    fun request() {
        trigger.request(onResume)
    }

    fun cancel() {
        trigger.cancel()
    }
}
