package com.geovault.tracker.replay

import kotlinx.serialization.json.Json

object CaptureReplaySessionLoader {
    private const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun load(resourceName: String): CaptureReplaySession {
        val stream = checkNotNull(
            CaptureReplaySessionLoader::class.java.classLoader?.getResourceAsStream("replay/$resourceName.json"),
        ) { "missing replay resource replay/$resourceName.json" }
        val dto = stream.bufferedReader().use { reader ->
            json.decodeFromString<CaptureReplaySessionDto>(reader.readText())
        }
        require(dto.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "unsupported replay schemaVersion=${dto.schemaVersion}"
        }
        require(dto.frames.size == dto.frameCount) {
            "frameCount mismatch: declared=${dto.frameCount} actual=${dto.frames.size}"
        }
        return CaptureReplaySession(
            sessionId = dto.sessionId,
            trackId = dto.trackId,
            wallBaseMs = dto.wallBaseMs,
            frames = dto.frames.map { it.toFrame() },
            milestones = dto.milestones.map { it.toMilestone() },
        )
    }

    private fun CaptureReplayFrameDto.toFrame(): CaptureReplayFrame {
        return CaptureReplayFrame(
            gpsTimeMs = gpsTimeMs,
            wallOffsetMs = wallOffsetMs,
            lat = lat,
            lon = lon,
            accuracy = accuracy.toFloat(),
            accepted = accepted,
            emission = emission,
            reject = reject,
            policy = policy,
            rawDistanceMeters = rawDistanceMeters,
            effectiveDistanceMeters = effectiveDistanceMeters,
            elapsedSeconds = elapsedSeconds,
            impliedSpeedMps = impliedSpeedMps,
            committedLat = committedLat,
            committedLon = committedLon,
        )
    }

    private fun CaptureReplayMilestoneDto.toMilestone(): CaptureReplayMilestone {
        return CaptureReplayMilestone(
            wallOffsetMs = wallOffsetMs,
            kind = kind,
            modeBefore = modeBefore,
            modeAfter = modeAfter,
            reason = reason,
            speedMps = speedMps?.toFloat(),
            accuracyMeters = accuracyMeters?.toFloat(),
            elapsedSeconds = elapsedSeconds,
            path = path,
        )
    }
}
