package com.geovault.tracker.location

internal class LowAccuracyFallbackCoordinator {
    private data class CandidateFingerprint(
        val latitude: Double,
        val longitude: Double,
        val timestampMs: Long
    )

    private companion object {
        private const val MIN_NEW_SAMPLE_TIME_DELTA_MS = 1_000L
        private const val MIN_NEW_SAMPLE_DISTANCE_METERS = 5f
    }

    private var awaitingLock: Boolean = false
    private var latestCandidate: CandidateFingerprint? = null
    private var lastEmittedCandidate: CandidateFingerprint? = null

    fun onRejectedFixForLock(
        fallbackEligible: Boolean,
        candidateLatitude: Double,
        candidateLongitude: Double,
        candidateTimestampMs: Long
    ): Boolean {
        if (!fallbackEligible) return false
        latestCandidate = CandidateFingerprint(
            latitude = candidateLatitude,
            longitude = candidateLongitude,
            timestampMs = candidateTimestampMs
        )
        val shouldStartTimer = !awaitingLock
        awaitingLock = true
        return shouldStartTimer
    }

    fun onAcceptedFix() {
        awaitingLock = false
        latestCandidate = null
        lastEmittedCandidate = null
    }

    fun onTrackingStopped() {
        awaitingLock = false
        latestCandidate = null
        lastEmittedCandidate = null
    }

    fun shouldEmitFallback(fallbackEligible: Boolean, hasCandidate: Boolean): Boolean {
        if (!fallbackEligible) return false
        if (!hasCandidate) return false
        if (!awaitingLock) return false
        val latest = latestCandidate ?: return false
        val emitted = lastEmittedCandidate ?: return true
        if (latest.timestampMs - emitted.timestampMs >= MIN_NEW_SAMPLE_TIME_DELTA_MS) return true
        val distanceMeters = distanceMeters(latest, emitted)
        return distanceMeters >= MIN_NEW_SAMPLE_DISTANCE_METERS
    }

    fun onFallbackEmitted(
        candidateLatitude: Double,
        candidateLongitude: Double,
        candidateTimestampMs: Long
    ) {
        lastEmittedCandidate = CandidateFingerprint(
            latitude = candidateLatitude,
            longitude = candidateLongitude,
            timestampMs = candidateTimestampMs
        )
    }

    private fun distanceMeters(a: CandidateFingerprint, b: CandidateFingerprint): Float {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val sinHalfLat = kotlin.math.sin(dLat / 2.0)
        val sinHalfLon = kotlin.math.sin(dLon / 2.0)
        val aHarv = sinHalfLat * sinHalfLat +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinHalfLon * sinHalfLon
        val c = 2.0 * kotlin.math.atan2(kotlin.math.sqrt(aHarv), kotlin.math.sqrt(1.0 - aHarv))
        return (earthRadiusMeters * c).toFloat()
    }
}
