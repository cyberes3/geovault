package com.geovault.tracker.replay

import android.location.Location

object CaptureReplayLocations {
    fun toLocation(frame: CaptureReplayFrame, provider: String = "gps"): Location {
        return Location(provider).apply {
            latitude = frame.lat
            longitude = frame.lon
            accuracy = frame.accuracy
            time = frame.gpsTimeMs
            val speed = frame.impliedSpeedMps.coerceAtLeast(0.0).toFloat()
            if (speed > 0f) {
                this.speed = speed
            }
        }
    }
}
