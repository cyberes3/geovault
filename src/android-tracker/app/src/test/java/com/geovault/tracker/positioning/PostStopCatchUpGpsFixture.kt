package com.geovault.tracker.positioning

import android.location.Location

internal object PostStopCatchUpGpsFixture {
    const val TRACKER_ID = "tracker-fixture-1"

    const val SESSION_START_MS = 1_780_428_796_644L
    const val ANCHOR_TIME_MS = 1_780_429_222_000L
    const val ANCHOR_LAT = 40.0
    const val ANCHOR_LON = -100.0
    const val ANCHOR_ACC = 6f

    data class CatchUpFix(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        val accuracy: Float,
        val speedMps: Float,
        val bearing: Float,
    )

    val catchUpCandidates: List<CatchUpFix> = listOf(
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 478_000L,
            lat = 40.02322845661646,
            lon = -99.97974588090182,
            accuracy = 8.67347f,
            speedMps = 28.015873f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 497_000L,
            lat = 40.021361639142086,
            lon = -99.97368107104262,
            accuracy = 8.67347f,
            speedMps = 28.656723f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 517_000L,
            lat = 40.01927928102767,
            lon = -99.9673636306223,
            accuracy = 11.22449f,
            speedMps = 28.582445f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 537_000L,
            lat = 40.018462590292145,
            lon = -99.96031445005492,
            accuracy = 14.285714f,
            speedMps = 29.456853f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 557_000L,
            lat = 40.01664589955715,
            lon = -99.95326526948756,
            accuracy = 14.285714f,
            speedMps = 29.1f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 577_000L,
            lat = 40.01482920882215,
            lon = -99.9462160889202,
            accuracy = 12f,
            speedMps = 28.8f,
            bearing = 90f,
        ),
    )

    fun anchorLocation(): Location {
        return Location("gps").apply {
            latitude = ANCHOR_LAT
            longitude = ANCHOR_LON
            accuracy = ANCHOR_ACC
            speed = 0f
            time = ANCHOR_TIME_MS
        }
    }

    fun catchUpLocation(fix: CatchUpFix): Location {
        return Location("gps").apply {
            latitude = fix.lat
            longitude = fix.lon
            accuracy = fix.accuracy
            speed = fix.speedMps
            bearing = fix.bearing
            time = fix.timeMs
        }
    }
}
