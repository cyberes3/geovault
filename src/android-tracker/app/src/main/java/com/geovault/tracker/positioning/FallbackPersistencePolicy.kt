package com.geovault.tracker.positioning

import android.location.Location

object FallbackPersistencePolicy {
    fun shouldPersistFallbackPoint(
        previousAcceptedLocation: Location?,
        fallbackLocation: Location,
    ): Boolean {
        return !isWithinCombinedAccuracyUncertainty(previousAcceptedLocation, fallbackLocation)
    }

    fun isWithinCombinedAccuracyUncertainty(
        previousAcceptedLocation: Location?,
        candidateLocation: Location,
    ): Boolean {
        val previous = previousAcceptedLocation ?: return false
        val distanceMeters = previous.distanceTo(candidateLocation).toDouble()
        if (distanceMeters <= 0.0) return true
        val previousAccuracyMeters = if (previous.hasAccuracy()) {
            previous.accuracy.toDouble().coerceAtLeast(0.0)
        } else {
            0.0
        }
        val candidateAccuracyMeters = if (candidateLocation.hasAccuracy()) {
            candidateLocation.accuracy.toDouble().coerceAtLeast(0.0)
        } else {
            0.0
        }
        val effectiveDistanceMeters = distanceMeters - previousAccuracyMeters - candidateAccuracyMeters
        return effectiveDistanceMeters <= 0.0
    }
}
