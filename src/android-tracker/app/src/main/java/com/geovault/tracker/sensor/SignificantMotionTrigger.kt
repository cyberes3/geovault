package com.geovault.tracker.sensor

/**
 * One-shot trigger for significant motion. When the sensor fires, [onTrigger] is invoked once.
 * Use [request] to arm and [cancel] to disarm.
 */
interface SignificantMotionTrigger {
    /**
     * Register for one significant-motion event. When the sensor fires, [onTrigger] is invoked.
     * After firing, the trigger is disarmed (one-shot). Call [request] again to re-arm.
     */
    fun request(onTrigger: () -> Unit)

    /** Cancel any pending trigger. No-op if not armed. */
    fun cancel()

    /** True if the significant-motion sensor is available on this device. */
    fun isAvailable(): Boolean
}
