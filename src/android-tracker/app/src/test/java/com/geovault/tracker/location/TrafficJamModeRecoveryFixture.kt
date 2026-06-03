package com.geovault.tracker.location

import android.location.Location

/** Filter/motion replay literals from capture log; coordinates offset together. */
internal object TrafficJamModeRecoveryFixture {
    const val TRACKER_ID = "6068d65c-9f93-4c68-bc79-577b84b4ef5d"

    /** Positioning `now` at the first recorded speed-cap-exceeded reject in the pair. */
    const val WALL_BASE_MS = 1_780_439_844_492L

    const val ANCHOR_LAT = 54.84348622
    const val ANCHOR_LON = -112.07702075
    const val ANCHOR_ACC = 10.2f
    const val ANCHOR_GPS_TIME_MS = 1_780_439_059_259L

    data class ReplayFix(
        /** Milliseconds after [WALL_BASE_MS] for positioning `now`. */
        val wallOffsetMs: Long,
        /** GPS / filter `ts` from positioning_decision_trace. */
        val gpsTimeMs: Long,
        val lat: Double,
        val lon: Double,
        val accuracy: Float,
        val impliedSpeedMps: Double,
        val elapsedSeconds: Double,
        val rawDistanceMeters: Double,
        val effectiveDistanceMeters: Double,
        val filterReason: String,
        val rejectReason: com.geovault.tracker.policy.TrackPointRejectReason? = null,
    )

    /** Last within-cap accept before the paired speed-cap-exceeded rejects. */
    val walkingDemotionAccept: ReplayFix = ReplayFix(
        wallOffsetMs = -293_368L,
        gpsTimeMs = 1_780_439_549_000L,
        lat = 54.83301810,
        lon = -112.07585517,
        accuracy = 21.428572f,
        impliedSpeedMps = 0.0,
        elapsedSeconds = 0.999999121,
        rawDistanceMeters = 9.986842142240581,
        effectiveDistanceMeters = 0.0,
        filterReason = "within-cap",
    )

    val highwayCapRejects: List<ReplayFix> = listOf(
        ReplayFix(
            wallOffsetMs = 0L,
            gpsTimeMs = 1_780_439_844_000L,
            lat = 54.76227685150051,
            lon = -112.0800841728775,
            accuracy = 8.67347f,
            impliedSpeedMps = 24.35184038366045,
            elapsedSeconds = 18.999984443,
            rawDistanceMeters = 482.0790559479116,
            effectiveDistanceMeters = 462.68458844796766,
            filterReason = "speed-cap-exceeded",
        ),
        ReplayFix(
            wallOffsetMs = 20_000L,
            gpsTimeMs = 1_780_439_864_000L,
            lat = 54.75652963195288,
            lon = -112.08101632432921,
            accuracy = 9.693877f,
            impliedSpeedMps = 31.552227799597457,
            elapsedSeconds = 19.999982999,
            rawDistanceMeters = 644.051722270568,
            effectiveDistanceMeters = 631.0440195725243,
            filterReason = "speed-cap-exceeded",
        ),
    )

    fun anchorLocation(): Location {
        return Location("gps").apply {
            latitude = ANCHOR_LAT
            longitude = ANCHOR_LON
            accuracy = ANCHOR_ACC
            speed = 0f
            time = ANCHOR_GPS_TIME_MS
        }
    }

    fun wallNowMs(fix: ReplayFix): Long = WALL_BASE_MS + fix.wallOffsetMs
}
