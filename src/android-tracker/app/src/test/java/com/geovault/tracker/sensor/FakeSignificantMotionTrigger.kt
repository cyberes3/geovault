package com.geovault.tracker.sensor

/**
 * Fake [SignificantMotionTrigger] for unit tests. Call [simulateTrigger] to invoke the callback
 * registered via [request]. One-shot: after [simulateTrigger] the callback is cleared until
 * [request] is called again.
 */
class FakeSignificantMotionTrigger : SignificantMotionTrigger {

    private var pendingCallback: (() -> Unit)? = null

    override fun isAvailable(): Boolean = true

    override fun request(onTrigger: () -> Unit) {
        pendingCallback = onTrigger
    }

    override fun cancel() {
        pendingCallback = null
    }

    /**
     * Simulate the significant-motion sensor firing. Invokes the callback if one was registered
     * via [request] and not yet fired or cancelled. Clears the callback after invoking (one-shot).
     */
    fun simulateTrigger() {
        pendingCallback?.invoke()
        pendingCallback = null
    }
}
