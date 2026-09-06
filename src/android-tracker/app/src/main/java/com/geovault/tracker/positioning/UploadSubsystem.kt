package com.geovault.tracker.positioning
import com.geovault.tracker.positioning.PositioningRuntime
import android.content.Intent
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.net.GeoVaultHttp
import com.geovault.tracker.R
import com.geovault.common.net.GeoVaultConnectivity
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.location.TrackingSyncPolicy
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal class UploadSubsystem(private val rt: PositioningRuntime) {
    fun startRetryJob(runGeneration: Int) {
        rt.state.retryJob?.cancel()
        rt.state.retryJob = rt.serviceScope.launch(Dispatchers.IO) {
            while (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                val baseDelay = TrackingSyncPolicy.nextRetryDelayMs(
                    consecutiveFailures = rt.state.consecutivePushFailures,
                    failureClass = rt.state.lastSyncFailureClass
                )
                val jitter = Random.nextLong(-TrackingServiceConstants.RETRY_JITTER_MS, TrackingServiceConstants.RETRY_JITTER_MS + 1)
                delay((baseDelay + jitter).coerceAtLeast(5_000L))
                if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) break
                val trackerId = rt.ports.selectedTrackerId()
                val count = rt.deps.database.locationDao().getCurrentSessionCountForTracker(
                    trackerId = trackerId,
                    sessionBoundaryId = rt.state.sessionBoundaryForBacklogId
                )
                if (count > 0) {
                    rt.upload.pushQueuedLocations(scope = QueueUploadScope.LIVE_ONLY)
                }
            }
        }
    }

    fun stopRetryJob() {
        rt.state.retryJob?.cancel()
        rt.state.retryJob = null
    }

    fun startBacklogUploader(sessionBoundaryId: Long, runGeneration: Int) {
        rt.state.backlogUploaderJob?.cancel()
        rt.state.backlogUploaderJob = rt.serviceScope.launch(Dispatchers.IO) {
            while (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                val trackerId = rt.ports.selectedTrackerId()
                val backlogCount = rt.deps.database.locationDao().getBacklogCountForTracker(
                    trackerId = trackerId,
                    sessionBoundaryId = sessionBoundaryId
                )
                if (backlogCount > 0) {
                    rt.upload.pushQueuedLocations(scope = QueueUploadScope.BACKLOG_ONLY)
                    delay(5_000L)
                } else {
                    delay(30_000L)
                }
            }
        }
    }

    fun stopBacklogUploader() {
        rt.state.backlogUploaderJob?.cancel()
        rt.state.backlogUploaderJob = null
    }

    fun startPreflightMonitor(runGeneration: Int) {
        rt.state.preflightJob?.cancel()
        rt.state.preflightJob = rt.serviceScope.launch(Dispatchers.IO) {
            while (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                delay(20_000L)
                if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) break
                if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(rt.ports.service)) {
                    withContext(Dispatchers.Main) {
                        rt.foreground.failActiveTrackingAndStop(rt.ports.service.getString(R.string.location_permissions_required))
                    }
                    return@launch
                }
                if (!rt.utilities.isGpsProviderEnabled()) {
                    withContext(Dispatchers.Main) {
                        rt.collection.enterWaitingForGpsProvider(reason = "preflight_monitor")
                    }
                    continue
                }
                if (
                    rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
                    rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                ) {
                    withContext(Dispatchers.Main) {
                        rt.collection.resumeFromGpsProviderWait(reason = "preflight_monitor")
                    }
                }
            }
        }
    }

    fun stopPreflightMonitor() {
        rt.state.preflightJob?.cancel()
        rt.state.preflightJob = null
    }

    suspend fun pushQueuedLocations(
        scope: QueueUploadScope,
        updateFailureCounters: Boolean = true
    ): SyncFailureClass {
        if (!rt.state.isTracking) return SyncFailureClass.NONE
        val trackerId = rt.ports.selectedTrackerId()
        trimQueuedLocationsRetention(trackerId)
        updateUploadQueueCounts(trackerId)
        if (!GeoVaultConnectivity.hasValidatedInternet(rt.ports.service)) {
            val result = QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.NO_NETWORK)
            rt.upload.applyQueueUploadResult(
                result = result,
                scope = scope,
                settings = rt.deps.settingsRepository.getSettings(),
                updateFailureCounters = updateFailureCounters,
            )
            if (scope != QueueUploadScope.BACKLOG_ONLY) {
                rt.state.lastSyncFailureClass = SyncFailureClass.TRANSIENT
            }
            withContext(Dispatchers.Main) {
                rt.projection.updateNotificationFromDb(broadcastStats = true)
            }
            return result.failureClass
        }
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(trackerId)) {
            rt.deps.runtimeTelemetry.event("queue_skip_invalid_tracker", "scope=$scope")
            val result = QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.INVALID_TRACKER)
            val trackerError = if (trackerId.isBlank()) {
                rt.ports.service.getString(R.string.no_tracker_selected_go_to_settings)
            } else {
                rt.ports.service.getString(R.string.tracker_validation_failed_go_to_settings)
            }
            rt.upload.applyQueueUploadResult(
                result = result,
                scope = scope,
                settings = rt.deps.settingsRepository.getSettings(),
                updateFailureCounters = updateFailureCounters,
            )
            withContext(Dispatchers.Main) {
                rt.ports.service.sendBroadcast(
                    Intent(TrackingServiceIntents.ACTION_TRACKING_ERROR).apply {
                        setPackage(rt.ports.service.packageName)
                        putExtra(TrackingServiceIntents.EXTRA_TRACKING_ERROR_MESSAGE, trackerError)
                    }
                )
                rt.projection.updateNotificationFromDb(broadcastStats = true)
            }
            return result.failureClass
        }
        val serverUrl = GeoVaultAuthSession.get().getServerUrl()
        val settings = rt.deps.settingsRepository.getSettings()
        val result = rt.deps.queueUploadEngine.push(
            scope = scope,
            trackerId = trackerId,
            serverUrl = serverUrl,
            config = QueueUploadConfig(
                sessionBoundaryId = rt.state.sessionBoundaryForBacklogId,
                sessionVisibleBoundaryId = rt.state.sessionVisibleBoundaryId,
                maxBatchesPerPush = TrackingServiceConstants.MAX_BATCHES_PER_PUSH,
                useExtendedParams = settings.sendExtendedData,
                sessionStartTimeMs = rt.state.runtimeSnapshot.sessionStartTimeMs,
                batteryLevel = rt.utilities.readBatteryLevel(),
                isCharging = rt.utilities.isCharging(),
                deviceIdentifier = rt.utilities.getDeviceIdentifier()
            ),
            onBatchUploaded = { visibleSentCount ->
                val sentDelta = visibleSentCount.coerceAtLeast(0)
                if (sentDelta > 0) {
                    rt.projection.updateRuntimeSnapshot {
                        it.copy(
                            pointsSentThisSession = it.pointsSentThisSession + sentDelta,
                            lastPointSentAtMs = rt.deps.clock.wallTimeMs(),
                        )
                    }
                }
            }
        )
        rt.upload.applyQueueUploadResult(
            result = result,
            scope = scope,
            settings = settings,
            updateFailureCounters = updateFailureCounters,
        )
        trimQueuedLocationsRetention(trackerId)
        updateUploadQueueCounts(trackerId)
        withContext(Dispatchers.Main) {
            rt.projection.updateNotificationFromDb(broadcastStats = true)
        }
        return result.failureClass
    }

    fun applyQueueUploadResult(
        result: QueueUploadResult,
        scope: QueueUploadScope,
        settings: TrackerSettings,
        updateFailureCounters: Boolean,
    ) {
        logQueueUploadResult(result = result, scope = scope)
        if (scope == QueueUploadScope.BACKLOG_ONLY) return
        val shouldUpdateCounters = updateFailureCounters && result.failureClass != SyncFailureClass.SKIPPED
        rt.state.lastSyncFailureClass = result.failureClass
        rt.state.uploadLivenessState = rt.state.uploadLivenessState.onUploadResult(
            result = result,
            nowMs = rt.deps.clock.wallTimeMs(),
            updateFailureCounters = shouldUpdateCounters,
        )
        if (result.rowsDeleted > 0) {
            val uploadedAtMs = rt.deps.clock.wallTimeMs()
            rt.deps.pointFreshnessTracker.markUploadSucceeded(uploadedAtMs)
            rt.projection.updateRuntimeSnapshot {
                it.copy(
                    lastPointSentAtMs = QueueUploadOutcomePolicy.lastPointSentAtMsAfterRowsDeleted(
                        previousLastPointSentAtMs = it.lastPointSentAtMs,
                        visibleRowsSent = result.visibleRowsSent,
                        uploadedAtMs = uploadedAtMs,
                    ),
                    lastUploadSucceededAtMs = rt.deps.pointFreshnessTracker.lastUploadSucceededAtMs,
                )
            }
        }
        if (!shouldUpdateCounters) return
        if (result.failureClass == SyncFailureClass.NONE) {
            rt.state.consecutivePushFailures = 0
            return
        }
        rt.state.consecutivePushFailures++
        val nowMs = rt.deps.clock.wallTimeMs()
        val motionMode = rt.contextBuilder.resolveActiveMotionMode()
        if (
            rt.deps.pointFreshnessTracker.isLocalFresh(
                nowMs = nowMs,
                intervalSec = rt.contextBuilder.resolvePointFreshnessIntervalSec(motionMode),
            )
        ) {
            rt.deps.runtimeTelemetry.event(
                "upload_failed_local_fresh",
                "failureClass=${result.failureClass} " +
                    "reason=${result.failureReason?.telemetryValue ?: result.skippedReason?.telemetryValue ?: "none"} " +
                    "consecutiveFailures=${rt.state.consecutivePushFailures} " +
                    "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                    "uploadAgeMs=${rt.deps.pointFreshnessTracker.uploadAgeMs(nowMs) ?: -1L}"
            )
        }
    }

    fun logQueueUploadResult(result: QueueUploadResult, scope: QueueUploadScope) {
        val (eventName, details) = PositioningDiagnosticEvent.queueUploadResult(result = result, scope = scope)
        rt.deps.runtimeTelemetry.event(eventName, details)
    }

    fun trimQueuedLocationsRetention(trackerId: String) {
        if (trackerId.isBlank()) return
        val cutoff = rt.deps.clock.wallTimeMs() - TrackingServiceConstants.MAX_QUEUE_AGE_MS
        val deletedByAge = rt.deps.database.locationDao().deleteOlderThanForTracker(trackerId, cutoff)
        val count = rt.deps.database.locationDao().getCountForTracker(trackerId)
        val deletedBySize = if (count > TrackingServiceConstants.MAX_QUEUE_SIZE) {
            rt.deps.database.locationDao().deleteOldestCountForTracker(trackerId, count - TrackingServiceConstants.MAX_QUEUE_SIZE)
        } else {
            0
        }
        if (deletedByAge > 0 || deletedBySize > 0) {
            rt.deps.runtimeTelemetry.event(
                name = "queue_retention_trim",
                details = "trackerId=$trackerId deletedByAge=$deletedByAge deletedBySize=$deletedBySize maxSize=$TrackingServiceConstants.MAX_QUEUE_SIZE maxAgeMs=$TrackingServiceConstants.MAX_QUEUE_AGE_MS"
            )
        }
    }

    fun updateUploadQueueCounts(trackerId: String) {
        if (trackerId.isBlank()) return
        rt.state.uploadLivenessState = rt.state.uploadLivenessState.withQueueCounts(
            currentSessionQueuedCount = rt.deps.database.locationDao().getCurrentSessionCountForTracker(
                trackerId = trackerId,
                sessionBoundaryId = rt.state.sessionBoundaryForBacklogId,
            ),
            backlogQueuedCount = rt.deps.database.locationDao().getBacklogCountForTracker(
                trackerId = trackerId,
                sessionBoundaryId = rt.state.sessionBoundaryForBacklogId,
            ),
        )
    }

    fun getAuthenticatedHttpClient(): OkHttpClient {
        if (rt.deps.httpClient == null) {
            rt.deps.httpClient = GeoVaultHttp.authenticatedClient().newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
        return rt.deps.httpClient!!
    }

}
