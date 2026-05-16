package com.geovault.tracker.policy.filter

/**
 * Tracks whether the current committed anchor is still trustworthy.
 *
 * Repeated snaps are useful for jitter suppression, but after the same
 * anchor is reused several times while raw fixes disagree with it, the
 * anchor should no longer be treated as a strong truth source.
 */
class AnchorHealthTracker(
    private var config: AnchorHealthConfig,
) {
    private var repeatedSnapCount: Int = 0
    private var disagreementCount: Int = 0

    val suspect: Boolean
        get() = repeatedSnapCount >= config.repeatedSnapLimit ||
            disagreementCount >= config.repeatedSnapLimit

    fun applyConfig(newConfig: AnchorHealthConfig) {
        config = newConfig
        repeatedSnapCount = repeatedSnapCount.coerceAtMost(config.repeatedSnapLimit)
        disagreementCount = disagreementCount.coerceAtMost(config.repeatedSnapLimit)
    }

    fun reset() {
        repeatedSnapCount = 0
        disagreementCount = 0
    }

    fun onCommit(rawDistanceMeters: Double) {
        if (rawDistanceMeters > config.disagreementDistanceMeters) {
            disagreementCount = (disagreementCount - 1).coerceAtLeast(0)
        } else {
            disagreementCount = 0
        }
        repeatedSnapCount = 0
    }

    fun onSnap(metrics: LocationMetrics) {
        repeatedSnapCount++
        if (metrics.rawDistanceMeters >= config.disagreementDistanceMeters ||
            metrics.accuracyMeters >= config.suspectAccuracyMeters
        ) {
            disagreementCount++
        }
    }

    fun onReject(metrics: LocationMetrics) {
        if (metrics.rawDistanceMeters >= config.disagreementDistanceMeters &&
            metrics.accuracyMeters >= config.suspectAccuracyMeters
        ) {
            disagreementCount++
        }
    }
}
