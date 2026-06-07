package com.geovault.tracker.replay.runtime

import android.location.Location
import com.geovault.tracker.settings.TrackerSettings
import kotlinx.serialization.Serializable

@Serializable
data class CaptureReplaySessionDto(
    val schemaVersion: Int,
    val sessionId: String,
    val trackId: String,
    val wallBaseMs: Long,
    val elapsedRealtimeBaseNanos: Long,
    val settings: CaptureReplaySettingsDto,
    val initialState: CaptureReplayInitialStateDto,
    val rawFixes: List<CaptureReplayRawFixDto>,
    val expectedEvents: List<CaptureReplayExpectedEventDto> = emptyList(),
    val assertions: CaptureReplayAssertionsDto = CaptureReplayAssertionsDto(),
)

@Serializable
data class CaptureReplaySettingsDto(
    val accuracyFilterMeters: Float,
    val lowAccuracyFallbackEnabled: Boolean,
    val lowAccuracyFallbackTimeoutSec: Long,
    val sendExtendedData: Boolean,
    val significantDataOnly: Boolean,
    val sparseTracking: Boolean,
    val startOnBoot: Boolean,
    val startTrackingOnLaunch: Boolean,
    val keepScreenOnWhileViewingMap: Boolean,
    val groupModeFitOnlyActiveTrackers: Boolean,
) {
    fun toSettings(): TrackerSettings = TrackerSettings(
        accuracyFilterMeters = accuracyFilterMeters,
        lowAccuracyFallbackEnabled = lowAccuracyFallbackEnabled,
        lowAccuracyFallbackTimeoutSec = lowAccuracyFallbackTimeoutSec,
        sendExtendedData = sendExtendedData,
        significantDataOnly = significantDataOnly,
        sparseTracking = sparseTracking,
        startOnBoot = startOnBoot,
        startTrackingOnLaunch = startTrackingOnLaunch,
        keepScreenOnWhileViewingMap = keepScreenOnWhileViewingMap,
        groupModeFitOnlyActiveTrackers = groupModeFitOnlyActiveTrackers,
    )
}

@Serializable
data class CaptureReplayInitialStateDto(
    val mode: String,
    val sessionBoundaryId: Long,
)

@Serializable
data class CaptureReplayRawFixDto(
    val index: Int,
    val wallOffsetMs: Long,
    val elapsedRealtimeOffsetNanos: Long,
    val gpsTimeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String = "gps",
    val mock: Boolean = false,
    val allowWhenGpsPaused: Boolean = false,
    val bypassFilters: Boolean = false,
    val skipAdaptiveTrackingEffects: Boolean = false,
) {
    fun wallTimeMs(session: CaptureReplaySessionDto): Long = session.wallBaseMs + wallOffsetMs

    fun elapsedRealtimeNanos(session: CaptureReplaySessionDto): Long =
        session.elapsedRealtimeBaseNanos + elapsedRealtimeOffsetNanos

    fun toLocation(session: CaptureReplaySessionDto): Location {
        return Location(provider).apply {
            time = gpsTimeMs
            elapsedRealtimeNanos = elapsedRealtimeNanos(session)
            latitude = lat
            longitude = lon
            this.accuracy = this@CaptureReplayRawFixDto.accuracy
            speedMps?.let { speed = it }
            bearingDeg?.let { bearing = it }
        }
    }
}

@Serializable
data class CaptureReplayExpectedEventDto(
    val kind: String,
    val wallOffsetMs: Long,
    val reason: String? = null,
    val path: String? = null,
)

@Serializable
data class CaptureReplayAssertionsDto(
    val finalMode: String = "DRIVING",
    val minPersistedPoints: Int = 0,
    val expectedMotionSeedCountMin: Int = 0,
    val maxDecisionMismatches: Int = 0,
    val requiredEvents: List<CaptureReplayRequiredEventDto> = emptyList(),
)

@Serializable
data class CaptureReplayRequiredEventDto(
    val kind: String,
    val reason: String,
    val path: String,
    val withinMs: Long,
    val fromWallOffsetMs: Long,
)
