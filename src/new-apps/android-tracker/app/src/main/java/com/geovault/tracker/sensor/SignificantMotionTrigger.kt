package com.geovault.tracker.sensor

interface SignificantMotionTrigger {
    fun request(onTrigger: () -> Unit)
    fun cancel()
    fun isAvailable(): Boolean
}
