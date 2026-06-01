package com.geovault.tracker.positioning

import android.location.Location

object ObservedSpeedResolver {
    fun resolveObservedSpeedMps(location: Location, referenceLocation: Location?): Float? {
        if (location.hasSpeed()) return location.speed.coerceAtLeast(0f)
        val previous = referenceLocation ?: return null
        val elapsedSec = (location.time - previous.time) / 1000f
        if (elapsedSec <= 0f) return null
        return (previous.distanceTo(location) / elapsedSec).coerceAtLeast(0f)
    }
}
