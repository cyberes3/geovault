package com.geovault.tracker.replay

import kotlinx.serialization.Serializable

@Serializable
data class CaptureReplaySessionDto(
    val schemaVersion: Int,
    val sessionId: String,
    val trackId: String,
    val wallBaseMs: Long,
    val frameCount: Int,
    val frames: List<CaptureReplayFrameDto>,
    val milestones: List<CaptureReplayMilestoneDto> = emptyList(),
)

@Serializable
data class CaptureReplayFrameDto(
    val gpsTimeMs: Long,
    val wallOffsetMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val accepted: Boolean,
    val emission: String,
    val reject: String,
    val policy: String,
    val rawDistanceMeters: Double,
    val effectiveDistanceMeters: Double,
    val elapsedSeconds: Double,
    val impliedSpeedMps: Double,
    val committedLat: Double? = null,
    val committedLon: Double? = null,
)

@Serializable
data class CaptureReplayMilestoneDto(
    val wallOffsetMs: Long,
    val kind: String,
    val modeBefore: String? = null,
    val modeAfter: String? = null,
    val reason: String? = null,
    val speedMps: Double? = null,
    val accuracyMeters: Double? = null,
    val elapsedSeconds: Double? = null,
    val path: String? = null,
)

data class CaptureReplayFrame(
    val gpsTimeMs: Long,
    val wallOffsetMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val accepted: Boolean,
    val emission: String,
    val reject: String,
    val policy: String,
    val rawDistanceMeters: Double,
    val effectiveDistanceMeters: Double,
    val elapsedSeconds: Double,
    val impliedSpeedMps: Double,
    val committedLat: Double?,
    val committedLon: Double?,
) {
    fun wallNowMs(session: CaptureReplaySession): Long = session.wallBaseMs + wallOffsetMs
}

data class CaptureReplayMilestone(
    val wallOffsetMs: Long,
    val kind: String,
    val modeBefore: String?,
    val modeAfter: String?,
    val reason: String?,
    val speedMps: Float?,
    val accuracyMeters: Float?,
    val elapsedSeconds: Double?,
    val path: String?,
) {
    fun wallNowMs(session: CaptureReplaySession): Long = session.wallBaseMs + wallOffsetMs
}

data class CaptureReplaySession(
    val sessionId: String,
    val trackId: String,
    val wallBaseMs: Long,
    val frames: List<CaptureReplayFrame>,
    val milestones: List<CaptureReplayMilestone>,
) {
    fun frameAtWallOffset(wallOffsetMs: Long): CaptureReplayFrame? =
        frames.firstOrNull { it.wallOffsetMs == wallOffsetMs }

    fun firstCapExceededFastEmitMilestone(): CaptureReplayMilestone? =
        milestones.firstOrNull {
            it.kind == "auto_motion_evidence" &&
                it.reason == "speed-cap-exceeded" &&
                it.path == "FAST_EMIT"
        }
}
