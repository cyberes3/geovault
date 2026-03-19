package com.geovault.tracker.pipeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class IngressRejectReason {
    INVALID_COORDINATES,
    OUT_OF_ORDER,
    DUPLICATE,
    BAD_ACCURACY,
    TOO_FAR_FUTURE,
    JUMP
}

data class IngressStats(
    val accepted: Long,
    val rejected: Long,
    val rejectedInvalidCoordinates: Long,
    val rejectedOutOfOrder: Long,
    val rejectedDuplicate: Long,
    val rejectedBadAccuracy: Long,
    val rejectedTooFarFuture: Long,
    val rejectedJump: Long
)

data class IngressSourceProfile(
    val maxAccuracyMeters: Float?,
    val maxFutureSkewMs: Long,
    val maxJumpSpeedMps: Double?
)

object UnifiedTrackPointIngress {
    private const val SECONDS_TO_MS_THRESHOLD = 1_000_000_000_000L
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val MIN_DT_FOR_SPEED_SECONDS = 0.5
    private val localProfile = IngressSourceProfile(
        maxAccuracyMeters = 100f,
        maxFutureSkewMs = 5 * 60 * 1000L,
        maxJumpSpeedMps = 95.0
    )
    private val remoteProfile = IngressSourceProfile(
        maxAccuracyMeters = 200f,
        maxFutureSkewMs = 5 * 60 * 1000L,
        maxJumpSpeedMps = 130.0
    )

    private data class LastPoint(
        val timestampMs: Long,
        val lon: Double,
        val lat: Double
    )

    private val lastPointByStream = ConcurrentHashMap<String, LastPoint>()
    private val acceptedCount = AtomicLong(0L)
    private val rejectedCount = AtomicLong(0L)
    private val rejectedInvalidCoordinates = AtomicLong(0L)
    private val rejectedOutOfOrder = AtomicLong(0L)
    private val rejectedDuplicate = AtomicLong(0L)
    private val rejectedBadAccuracy = AtomicLong(0L)
    private val rejectedTooFarFuture = AtomicLong(0L)
    private val rejectedJump = AtomicLong(0L)

    fun sanitize(event: TrackPointEvent, nowMs: Long = System.currentTimeMillis()): TrackPointEvent? {
        if (event.lat !in -90.0..90.0 || event.lon !in -180.0..180.0) {
            reject(IngressRejectReason.INVALID_COORDINATES)
            return null
        }

        val profile = when (event.source) {
            TrackPointSource.LOCAL_GPS -> localProfile
            TrackPointSource.REMOTE_STREAM -> remoteProfile
        }
        val accuracyMeters = event.accuracyMeters
        if (profile.maxAccuracyMeters != null &&
            accuracyMeters != null &&
            accuracyMeters > profile.maxAccuracyMeters
        ) {
            reject(IngressRejectReason.BAD_ACCURACY)
            return null
        }
        val normalizedTimestampMs = normalizeTimestamp(event.timestampMs, nowMs)
        if (normalizedTimestampMs - nowMs > profile.maxFutureSkewMs) {
            reject(IngressRejectReason.TOO_FAR_FUTURE)
            return null
        }
        val key = "${event.source}:${event.trackId}"
        val previous = lastPointByStream[key]
        if (previous != null) {
            if (normalizedTimestampMs < previous.timestampMs) {
                reject(IngressRejectReason.OUT_OF_ORDER)
                return null
            }
            if (normalizedTimestampMs == previous.timestampMs &&
                abs(event.lon - previous.lon) < 1e-9 &&
                abs(event.lat - previous.lat) < 1e-9
            ) {
                reject(IngressRejectReason.DUPLICATE)
                return null
            }
            if (profile.maxJumpSpeedMps != null && isJump(previous, event, normalizedTimestampMs, profile.maxJumpSpeedMps)) {
                reject(IngressRejectReason.JUMP)
                return null
            }
        }
        lastPointByStream[key] = LastPoint(
            timestampMs = normalizedTimestampMs,
            lon = event.lon,
            lat = event.lat
        )
        acceptedCount.incrementAndGet()
        return event.copy(timestampMs = normalizedTimestampMs)
    }

    fun stats(): IngressStats {
        return IngressStats(
            accepted = acceptedCount.get(),
            rejected = rejectedCount.get(),
            rejectedInvalidCoordinates = rejectedInvalidCoordinates.get(),
            rejectedOutOfOrder = rejectedOutOfOrder.get(),
            rejectedDuplicate = rejectedDuplicate.get(),
            rejectedBadAccuracy = rejectedBadAccuracy.get(),
            rejectedTooFarFuture = rejectedTooFarFuture.get(),
            rejectedJump = rejectedJump.get()
        )
    }

    fun resetForTests() {
        lastPointByStream.clear()
        acceptedCount.set(0L)
        rejectedCount.set(0L)
        rejectedInvalidCoordinates.set(0L)
        rejectedOutOfOrder.set(0L)
        rejectedDuplicate.set(0L)
        rejectedBadAccuracy.set(0L)
        rejectedTooFarFuture.set(0L)
        rejectedJump.set(0L)
    }

    private fun normalizeTimestamp(timestampMs: Long, nowMs: Long): Long {
        if (timestampMs <= 0L) return nowMs
        return if (timestampMs in 1 until SECONDS_TO_MS_THRESHOLD) {
            timestampMs * 1000L
        } else {
            timestampMs
        }
    }

    private fun reject(reason: IngressRejectReason) {
        rejectedCount.incrementAndGet()
        when (reason) {
            IngressRejectReason.INVALID_COORDINATES -> rejectedInvalidCoordinates.incrementAndGet()
            IngressRejectReason.OUT_OF_ORDER -> rejectedOutOfOrder.incrementAndGet()
            IngressRejectReason.DUPLICATE -> rejectedDuplicate.incrementAndGet()
            IngressRejectReason.BAD_ACCURACY -> rejectedBadAccuracy.incrementAndGet()
            IngressRejectReason.TOO_FAR_FUTURE -> rejectedTooFarFuture.incrementAndGet()
            IngressRejectReason.JUMP -> rejectedJump.incrementAndGet()
        }
    }

    private fun isJump(
        previous: LastPoint,
        event: TrackPointEvent,
        normalizedTimestampMs: Long,
        maxJumpSpeedMps: Double
    ): Boolean {
        val dtSeconds = (normalizedTimestampMs - previous.timestampMs) / 1000.0
        if (dtSeconds < MIN_DT_FOR_SPEED_SECONDS) return false
        val distanceMeters = haversineMeters(
            lat1 = previous.lat,
            lon1 = previous.lon,
            lat2 = event.lat,
            lon2 = event.lon
        )
        val speedMps = distanceMeters / dtSeconds
        return speedMps > maxJumpSpeedMps
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latRad1 = Math.toRadians(lat1)
        val lonRad1 = Math.toRadians(lon1)
        val latRad2 = Math.toRadians(lat2)
        val lonRad2 = Math.toRadians(lon2)
        val dLat = latRad2 - latRad1
        val dLon = lonRad2 - lonRad1
        val sinHalfLat = sin(dLat / 2.0)
        val sinHalfLon = sin(dLon / 2.0)
        val a = sinHalfLat.pow(2.0) + cos(latRad1) * cos(latRad2) * sinHalfLon.pow(2.0)
        val boundedA = a.coerceIn(0.0, 1.0)
        val c = 2.0 * asin(sqrt(boundedA))
        return EARTH_RADIUS_M * c
    }
}
