package com.geovault.tracker.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.app.NotificationCompat
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.TrackingService
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.runtime.RuntimeServiceEvent
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.settings.TrackerSettings

class TrackingSessionCoordinator {
    fun transitionToRunning(previous: TrackingRuntimeSnapshot, nowMs: Long): TrackingRuntimeSnapshot {
        return previous.copy(
            isRunning = true,
            lifecycleState = TrackingLifecycleState.RUNNING,
            failureReason = null,
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
    val lastFilteredLocation: Location?,
    val queuedPointsVisible: Int,
    val lastAccuracyMeters: Float?,
    val lastTrackedLatitude: Double?,
    val lastTrackedLongitude: Double?,
    val lastTrackedTimestampMs: Long,
    val lastTrackedPropsJson: String?
)

class LocationIngestCoordinator(private val locationDao: LocationDao) {
    fun ingest(
        location: Location,
        settings: TrackerSettings,
        previousAcceptedLocation: Location?,
        sessionVisibleBoundaryId: Long,
        maxQueueSize: Int,
        bypassFilters: Boolean,
        propsJson: String?,
        totalDistanceMeters: Float
    ): LocationIngestResult {
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        if (!bypassFilters) {
            if (!TrackingLocationPolicy.acceptByAccuracy(location, settings.accuracyFilterMeters)) {
                return ignored(previousAcceptedLocation, accuracy, propsJson)
            }
            if (TrackingLocationPolicy.isJump(previousAcceptedLocation, location)) {
                return ignored(previousAcceptedLocation, accuracy, propsJson)
            }
            val minDist = settings.distanceFilterMeters
            if (previousAcceptedLocation != null && previousAcceptedLocation.distanceTo(location) < minDist) {
                return ignored(previousAcceptedLocation, accuracy, propsJson)
            }
        }

        val queued = QueuedLocation.fromLocation(location, totalDistanceMeters = totalDistanceMeters)
        locationDao.insert(queued)
        trimQueueIfNeeded(maxQueueSize)
        val visible = locationDao.getCurrentSessionCountById(sessionVisibleBoundaryId)
        return LocationIngestResult(
            accepted = true,
            lastFilteredLocation = Location(location),
            queuedPointsVisible = visible,
            lastAccuracyMeters = accuracy,
            lastTrackedLatitude = location.latitude,
            lastTrackedLongitude = location.longitude,
            lastTrackedTimestampMs = location.time,
            lastTrackedPropsJson = propsJson
        )
    }

    private fun trimQueueIfNeeded(maxQueueSize: Int) {
        val count = locationDao.getCount()
        if (count > maxQueueSize) {
            locationDao.deleteOldestCount(count - maxQueueSize)
        }
    }

    private fun ignored(previousAcceptedLocation: Location?, accuracy: Float?, propsJson: String?): LocationIngestResult {
        return LocationIngestResult(
            accepted = false,
            lastFilteredLocation = previousAcceptedLocation,
            queuedPointsVisible = 0,
            lastAccuracyMeters = accuracy,
            lastTrackedLatitude = null,
            lastTrackedLongitude = null,
            lastTrackedTimestampMs = 0L,
            lastTrackedPropsJson = propsJson
        )
    }
}

class TrackingNotificationPresenter(private val context: Context) {
    fun buildTrackingNotification(sentCount: Int, queuedCount: Int): Notification {
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
        val text = context.getString(R.string.tracking_notification_counts_line, sentCount, queuedCount)
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

    fun updateForegroundNotification(sentCount: Int, queuedCount: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            TrackingService.NOTIFICATION_ID,
            buildTrackingNotification(sentCount, queuedCount)
        )
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
