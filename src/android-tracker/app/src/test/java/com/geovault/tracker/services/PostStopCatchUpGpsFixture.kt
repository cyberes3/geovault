package com.geovault.tracker.services

import android.location.Location

internal object PostStopCatchUpGpsFixture {
    const val TRACKER_ID = "tracker-fixture-1"

    const val SESSION_START_MS = 1_780_428_796_644L
    const val ANCHOR_TIME_MS = 1_780_429_222_000L
    const val ANCHOR_LAT = 37.12038449433023
    const val ANCHOR_LON = -98.4518771982938
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
            lat = 37.14361295094669,
            lon = -98.43162307919562,
            accuracy = 8.67347f,
            speedMps = 28.015873f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 497_000L,
            lat = 37.14174613347232,
            lon = -98.42555826933642,
            accuracy = 8.67347f,
            speedMps = 28.656723f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 517_000L,
            lat = 37.1396637753579,
            lon = -98.4192408289161,
            accuracy = 11.22449f,
            speedMps = 28.582445f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 537_000L,
            lat = 37.13884708462238,
            lon = -98.41219164834873,
            accuracy = 14.285714f,
            speedMps = 29.456853f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 557_000L,
            lat = 37.13703039388738,
            lon = -98.40514246778136,
            accuracy = 14.285714f,
            speedMps = 29.1f,
            bearing = 90f,
        ),
        CatchUpFix(
            timeMs = ANCHOR_TIME_MS + 577_000L,
            lat = 37.13521370315238,
            lon = -98.398093287214,
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
