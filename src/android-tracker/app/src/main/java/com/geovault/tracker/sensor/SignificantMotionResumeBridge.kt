package com.geovault.tracker.sensor

interface SignificantMotionResumeGateway {
    fun request()
    fun cancel()
    fun isAvailable(): Boolean
}

class SignificantMotionResumeBridge(
    private val trigger: SignificantMotionTrigger,
    private val onResume: () -> Unit
) : SignificantMotionResumeGateway {
    override fun request() {
        trigger.request(onResume)
    }

    override fun cancel() {
        trigger.cancel()
    }

    override fun isAvailable(): Boolean = trigger.isAvailable()
}
