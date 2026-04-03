package com.geovault.tracker.sensor

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

    fun isAvailable(): Boolean = trigger.isAvailable()
}
