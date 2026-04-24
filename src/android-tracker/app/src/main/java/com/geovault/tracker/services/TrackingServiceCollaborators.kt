package com.geovault.tracker.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.core.location.LocationCompat
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackingService
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointPolicyConfig
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.runtime.RuntimeServiceEvent
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.settings.TrackerSettings
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TrackingSessionCoordinator {
    fun transitionToRunning(
        previous: TrackingRuntimeSnapshot,
        nowMs: Long,
        sessionVisibleBoundaryId: Long
    ): TrackingRuntimeSnapshot {
        return previous.copy(
            isRunning = true,
            lifecycleState = TrackingLifecycleState.RUNNING,
            failureReason = null,
            sessionVisibleBoundaryId = sessionVisibleBoundaryId,
            sessionStartTimeMs = nowMs,
            pointsSentThisSession = 0,
            lastPointSentAtMs = 0L,
            queuedPointsVisible = 0,
            sessionTotalDistanceMeters = 0f,
            lastAccuracyMeters = null,
            lastTrackedLatitude = null,
            lastTrackedLongitude = null,
            lastTrackedTimestampMs = 0L,
            lastTrackedPropsJson = null
        )
    }

    fun transitionToStopped(previous: TrackingRuntimeSnapshot, failureReason: String?): TrackingRuntimeSnapshot {
        return previous.copy(
            isRunning = false,
            lifecycleState = TrackingLifecycleState.STOPPED,
            failureReason = failureReason,
            sessionVisibleBoundaryId = 0L,
            sessionStartTimeMs = 0L,
            pointsSentThisSession = 0,
            lastPointSentAtMs = 0L,
            queuedPointsVisible = 0,
            sessionTotalDistanceMeters = 0f,
            lastAccuracyMeters = null,
            lastTrackedLatitude = null,
            lastTrackedLongitude = null,
            lastTrackedTimestampMs = 0L,
            lastTrackedPropsJson = null
        )
    }
}

data class LocationIngestResult(
    val accepted: Boolean,
    val rejectReason: TrackPointRejectReason? = null,
    val adjustmentReason: String? = null,
    val trackPointQuality: TrackPointQuality? = null,
    val pointPersisted: Boolean = false,
    val persistedRowId: Long? = null,
    val nextSessionDistanceMeters: Float,
    val lastFilteredLocation: Location?,
    val queuedPointsVisible: Int,
    val lastAccuracyMeters: Float?,
    val lastTrackedLatitude: Double?,
    val lastTrackedLongitude: Double?,
    val lastTrackedTimestampMs: Long,
    val lastTrackedPropsJson: String?
)

class LocationIngestCoordinator(private val locationDao: LocationDao) {
    private val lastAcceptedByStream = ConcurrentHashMap<String, TrackPointEvent>()
    private val acceptedHistoryByStream = ConcurrentHashMap<String, ArrayDeque<TrackPointEvent>>()
    private val jumpRejectStreakByStream = ConcurrentHashMap<String, AtomicLong>()

    @Synchronized
    fun resetSession(trackId: String) {
        resetLocalSession(trackId)
    }

    fun ingest(
        trackId: String,
        location: Location,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        effectiveAccuracyFilterMeters: Float = settings.accuracyFilterMeters,
        previousAcceptedLocation: Location?,
        sessionVisibleBoundaryId: Long,
        bypassFilters: Boolean,
        propsJson: String?,
        totalDistanceMeters: Float,
        queuedTrackerId: String,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        isMockLocation: Boolean = LocationCompat.isMock(location)
    ): LocationIngestResult {
        require(queuedTrackerId.isNotBlank()) { "queuedTrackerId must not be blank" }
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        var resolvedQuality: TrackPointQuality? = null
        if (!bypassFilters) {
            val decision = evaluatePolicyDecision(
                trackId = trackId,
                location = location,
                previousAcceptedLocation = previousAcceptedLocation,
                maxAccuracyMeters = effectiveAccuracyFilterMeters,
                motionMode = motionMode,
                isMockLocation = isMockLocation,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
            )
            if (!decision.accepted || decision.canonicalEvent == null) {
                return ignored(
                    previousAcceptedLocation = previousAcceptedLocation,
                    accuracy = accuracy,
                    propsJson = propsJson,
                    rejectReason = decision.rejectReason,
                    currentSessionDistanceMeters = totalDistanceMeters
                )
            }
            val canonical = decision.canonicalEvent
            resolvedQuality = canonical.quality
            location.latitude = canonical.lat
            location.longitude = canonical.lon
            location.time = canonical.timestampMs
            if (canonical.elapsedRealtimeNanos != null) {
                location.elapsedRealtimeNanos = canonical.elapsedRealtimeNanos
            }
            if (decision.adjustmentReason == TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED) {
                val visible = locationDao.getCurrentSessionCountById(sessionVisibleBoundaryId)
                val nextSessionDistanceMeters = computeNextSessionDistanceMeters(
                    currentSessionDistanceMeters = totalDistanceMeters,
                    previousAcceptedLocation = previousAcceptedLocation,
                    acceptedLocation = location
                )
                return LocationIngestResult(
                    accepted = true,
                    rejectReason = null,
                    adjustmentReason = decision.adjustmentReason,
                    trackPointQuality = canonical.quality,
                    pointPersisted = false,
                    persistedRowId = null,
                    nextSessionDistanceMeters = nextSessionDistanceMeters,
                    lastFilteredLocation = Location(location),
                    queuedPointsVisible = visible,
                    lastAccuracyMeters = accuracy,
                    lastTrackedLatitude = location.latitude,
                    lastTrackedLongitude = location.longitude,
                    lastTrackedTimestampMs = location.time,
                    lastTrackedPropsJson = propsJson
                )
            }
        }

        val nextSessionDistanceMeters = computeNextSessionDistanceMeters(
            currentSessionDistanceMeters = totalDistanceMeters,
            previousAcceptedLocation = previousAcceptedLocation,
            acceptedLocation = location
        )
        val queued = QueuedLocation.fromLocation(
            loc = location,
            trackerId = queuedTrackerId,
            totalDistanceMeters = nextSessionDistanceMeters,
        )
        val insertedId = locationDao.insert(queued)
        if (bypassFilters) {
            val canonical = trackPointEventForPolicy(
                trackId = trackId,
                location = location,
                isMockLocation = isMockLocation,
                nowMs = nowMs
            )
            updateAcceptedStateForLocalStream(trackId = trackId, canonical = canonical, historyWindowSize = 5)
        }
        val visible = locationDao.getCurrentSessionCountById(sessionVisibleBoundaryId)
        return LocationIngestResult(
            accepted = true,
            rejectReason = null,
            adjustmentReason = null,
            trackPointQuality = resolvedQuality,
            pointPersisted = true,
            persistedRowId = insertedId,
            nextSessionDistanceMeters = nextSessionDistanceMeters,
            lastFilteredLocation = Location(location),
            queuedPointsVisible = visible,
            lastAccuracyMeters = accuracy,
            lastTrackedLatitude = location.latitude,
            lastTrackedLongitude = location.longitude,
            lastTrackedTimestampMs = location.time,
            lastTrackedPropsJson = propsJson
        )
    }

    private fun evaluatePolicyDecision(
        trackId: String,
        location: Location,
        previousAcceptedLocation: Location?,
        maxAccuracyMeters: Float,
        motionMode: TrackingMotionMode,
        isMockLocation: Boolean,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long
    ): com.geovault.tracker.policy.TrackPointDecision {
        val event = trackPointEventForPolicy(trackId = trackId, location = location, isMockLocation = isMockLocation, nowMs = nowMs)
        // Pipeline derives "previous" from pipeline-local accepted state.
        // This intentionally avoids anchoring policy to bypass-only points.
        val config = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = maxAccuracyMeters,
            motionMode = motionMode,
            isMockLocation = isMockLocation
        )
        return evaluateWithState(
            trackId = trackId,
            event = event,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            config = config
        )
    }

    @Synchronized
    private fun evaluateWithState(
        trackId: String,
        event: TrackPointEvent,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        config: TrackPointPolicyConfig
    ): com.geovault.tracker.policy.TrackPointDecision {
        return TrackPointCrossSourceState.withLock {
            val streamKey = localStreamKey(trackId)
            val currentPreviousByStream = lastAcceptedByStream[streamKey]
            val currentPreviousByTrack = TrackPointCrossSourceState.previous(trackId)
            val history = acceptedHistoryByStream[streamKey]?.toList() ?: emptyList()
            var decision = TrackPointPolicyEngine.evaluate(
                event = event,
                previous = currentPreviousByStream,
                history = history,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                rawConfig = config
            )
            var effectivePreviousByTrack = currentPreviousByTrack
            if (!decision.accepted &&
                shouldForceLocalStallReanchor(
                    streamKey = streamKey,
                    reason = decision.rejectReason,
                    previousByTrack = effectivePreviousByTrack,
                    nowMs = nowMs
                )
            ) {
                resetLocalSession(trackId)
                effectivePreviousByTrack = null
                decision = TrackPointPolicyEngine.evaluate(
                    event = event,
                    previous = null,
                    history = emptyList(),
                    nowMs = nowMs,
                    nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                    rawConfig = config
                )
            }
            if (!decision.accepted || decision.canonicalEvent == null) {
                updateJumpRejectStreak(streamKey, decision.rejectReason)
                return@withLock decision
            }
            val canonical = decision.canonicalEvent
            if (isDuplicateAgainstTrack(effectivePreviousByTrack, canonical)) {
                updateJumpRejectStreak(streamKey, TrackPointRejectReason.DUPLICATE)
                return@withLock decision.copy(
                    accepted = false,
                    canonicalEvent = null,
                    rejectReason = TrackPointRejectReason.DUPLICATE
                )
            }
            if (isOutOfOrderAgainstTrack(effectivePreviousByTrack, canonical)) {
                updateJumpRejectStreak(streamKey, TrackPointRejectReason.OUT_OF_ORDER)
                return@withLock decision.copy(
                    accepted = false,
                    canonicalEvent = null,
                    rejectReason = TrackPointRejectReason.OUT_OF_ORDER
                )
            }
            lastAcceptedByStream[streamKey] = canonical
            TrackPointCrossSourceState.update(trackId, canonical)
            appendHistory(streamKey, canonical, config.rollingWindowSize)
            jumpRejectStreakByStream.remove(streamKey)
            decision.copy(canonicalEvent = canonical)
        }
    }

    private fun shouldForceLocalStallReanchor(
        streamKey: String,
        reason: TrackPointRejectReason?,
        previousByTrack: TrackPointEvent?,
        nowMs: Long
    ): Boolean {
        if (reason != TrackPointRejectReason.JUMP) return false
        val previous = previousByTrack ?: return false
        val anchorAgeMs = nowMs - previous.timestampMs
        if (anchorAgeMs < TrackingPolicyProfiles.LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS) return false
        val nextStreak = (jumpRejectStreakByStream[streamKey]?.get() ?: 0L) + 1L
        return nextStreak >= TrackingPolicyProfiles.LOCAL_STALL_REJECT_STREAK_THRESHOLD
    }

    private fun updateJumpRejectStreak(streamKey: String, reason: TrackPointRejectReason?) {
        if (reason == TrackPointRejectReason.JUMP) {
            jumpRejectStreakByStream.getOrPut(streamKey) { AtomicLong(0L) }.incrementAndGet()
        } else {
            jumpRejectStreakByStream.remove(streamKey)
        }
    }

    private fun appendHistory(streamKey: String, event: TrackPointEvent, windowSize: Int) {
        val history = acceptedHistoryByStream.getOrPut(streamKey) { ArrayDeque() }
        history.addLast(event)
        val maxHistory = windowSize.coerceIn(3, 20)
        while (history.size > maxHistory) {
            history.removeFirst()
        }
    }

    private fun isOutOfOrderAgainstTrack(previousByTrack: TrackPointEvent?, canonical: TrackPointEvent): Boolean {
        val previousTs = previousByTrack?.timestampMs ?: return false
        return canonical.timestampMs < previousTs
    }

    private fun isDuplicateAgainstTrack(previousByTrack: TrackPointEvent?, canonical: TrackPointEvent): Boolean {
        val previous = previousByTrack ?: return false
        return canonical.timestampMs == previous.timestampMs &&
            canonical.lon == previous.lon &&
            canonical.lat == previous.lat
    }

    private fun resetLocalSession(trackId: String) {
        val streamKey = localStreamKey(trackId)
        lastAcceptedByStream.remove(streamKey)
        acceptedHistoryByStream.remove(streamKey)
        jumpRejectStreakByStream.remove(streamKey)
        TrackPointCrossSourceState.resetTrack(trackId)
    }

    private fun localStreamKey(trackId: String): String {
        return "${TrackPointSource.LOCAL_GPS}:$trackId"
    }

    private fun updateAcceptedStateForLocalStream(
        trackId: String,
        canonical: TrackPointEvent,
        historyWindowSize: Int
    ) {
        TrackPointCrossSourceState.withLock {
            val streamKey = localStreamKey(trackId)
            lastAcceptedByStream[streamKey] = canonical
            TrackPointCrossSourceState.update(trackId, canonical)
            appendHistory(streamKey, canonical, historyWindowSize)
            jumpRejectStreakByStream.remove(streamKey)
        }
    }

    private fun trackPointEventForPolicy(
        trackId: String,
        location: Location,
        isMockLocation: Boolean,
        nowMs: Long
    ): TrackPointEvent {
        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(location.time, nowMs)
        val timestampSkewMs = abs(normalizedTimestampMs - nowMs)
        val timestampForPolicyMs = if (
            isMockLocation &&
            timestampSkewMs > TrackingPolicyProfiles.MOCK_TIMESTAMP_SKEW_TOLERANCE_MS
        ) {
            nowMs
        } else {
            normalizedTimestampMs
        }
        return TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = location.longitude,
            lat = location.latitude,
            timestampMs = timestampForPolicyMs,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos
        )
    }

    private fun ignored(
        previousAcceptedLocation: Location?,
        accuracy: Float?,
        propsJson: String?,
        rejectReason: TrackPointRejectReason?,
        currentSessionDistanceMeters: Float
    ): LocationIngestResult {
        return LocationIngestResult(
            accepted = false,
            rejectReason = rejectReason,
            adjustmentReason = null,
            pointPersisted = false,
            nextSessionDistanceMeters = currentSessionDistanceMeters,
            lastFilteredLocation = previousAcceptedLocation,
            queuedPointsVisible = 0,
            lastAccuracyMeters = accuracy,
            lastTrackedLatitude = null,
            lastTrackedLongitude = null,
            lastTrackedTimestampMs = 0L,
            lastTrackedPropsJson = propsJson
        )
    }

    private fun computeNextSessionDistanceMeters(
        currentSessionDistanceMeters: Float,
        previousAcceptedLocation: Location?,
        acceptedLocation: Location
    ): Float {
        val distanceDeltaMeters = if (previousAcceptedLocation != null) {
            previousAcceptedLocation.distanceTo(acceptedLocation).coerceAtLeast(0f)
        } else {
            0f
        }
        return currentSessionDistanceMeters + distanceDeltaMeters
    }
}

class TrackingNotificationPresenter(private val context: Context) {
    fun buildTrackingNotification(snapshot: TrackingRuntimeSnapshot): Notification {
        return buildTrackingNotification(
            sentCount = snapshot.pointsSentThisSession,
            queuedCount = snapshot.queuedPointsVisible,
            uiStatus = snapshot.uiStatus
        )
    }

    fun buildTrackingNotification(sentCount: Int, queuedCount: Int, uiStatus: TrackingUiStatus): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
            setPackage(context.packageName)
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = Intent(TrackingService.NOTIFICATION_DISMISSED_ACTION).apply {
            setPackage(context.packageName)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val status = context.getString(statusTextRes(uiStatus))
        val counts = context.getString(R.string.tracking_notification_counts_line, sentCount, queuedCount)
        val text = "$status\n$counts"
        return NotificationCompat.Builder(context, TrackingService.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.live_tracker_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.stop_tracking), stopPendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(dismissPendingIntent)
            .build()
    }

    fun updateForegroundNotification(sentCount: Int, queuedCount: Int, uiStatus: TrackingUiStatus) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            TrackingService.NOTIFICATION_ID,
            buildTrackingNotification(sentCount, queuedCount, uiStatus)
        )
    }

    fun updateForegroundNotification(snapshot: TrackingRuntimeSnapshot) {
        updateForegroundNotification(
            sentCount = snapshot.pointsSentThisSession,
            queuedCount = snapshot.queuedPointsVisible,
            uiStatus = snapshot.uiStatus
        )
    }

    private fun statusTextRes(status: TrackingUiStatus): Int {
        return when (status) {
            TrackingUiStatus.NOT_TRACKING -> R.string.tracking_status_not_tracking
            TrackingUiStatus.WAITING_FOR_GPS -> R.string.tracking_status_waiting_for_gps
            TrackingUiStatus.LOCKING -> R.string.tracking_status_locking
            TrackingUiStatus.TRACKING_ACTIVE -> R.string.tracking_status_active
        }
    }
}

class RuntimeEventPublisher(private val appContext: Context) {
    fun publish(type: RuntimeServiceEventType, reason: String, trigger: RuntimeTrigger = RuntimeTrigger.UNKNOWN) {
        TrackingRuntimeController.get(appContext).recordServiceEvent(
            RuntimeServiceEvent(
                type = type,
                trigger = trigger,
                reason = reason
            )
        )
    }
}
