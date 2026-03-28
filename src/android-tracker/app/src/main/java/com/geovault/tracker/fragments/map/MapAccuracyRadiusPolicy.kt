package com.geovault.tracker.fragments.map

internal data class AccuracyRadiusInput(
    val streamedAccuracyMeters: Float?,
    val trackingServiceAccuracyMeters: Float?,
    val allowTrackingServiceFallback: Boolean
)

internal object MapAccuracyRadiusPolicy {
    fun resolveAccuracyRadiusMeters(input: AccuracyRadiusInput): Double {
        val streamedAccuracyMeters = sanitizeAccuracyMeters(input.streamedAccuracyMeters)
        if (streamedAccuracyMeters != null) {
            return streamedAccuracyMeters.toDouble()
        }
        if (!input.allowTrackingServiceFallback) {
            return 0.0
        }
        val trackingServiceAccuracyMeters = sanitizeAccuracyMeters(input.trackingServiceAccuracyMeters)
        return trackingServiceAccuracyMeters?.toDouble() ?: 0.0
    }

    private fun sanitizeAccuracyMeters(value: Float?): Float? {
        return value?.takeIf { it.isFinite() && it > 0f }
    }
}
