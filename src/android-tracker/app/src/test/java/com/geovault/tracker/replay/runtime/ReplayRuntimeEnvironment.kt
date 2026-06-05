package com.geovault.tracker.replay.runtime

import android.app.Notification
import android.app.Service
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.positioning.PositioningRuntimeEnvironment
import com.geovault.tracker.positioning.time.PositioningClock
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.sensor.ActivityHintSource
import com.geovault.tracker.sensor.SignificantMotionResumeGateway
import com.geovault.tracker.services.LocationSessionGateway
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadGateway
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.TrackingNotificationGateway
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingUiStatus
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsDefaults
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerSettingsState
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.google.android.gms.location.LocationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import kotlin.coroutines.CoroutineContext

internal class ReplayRuntimeEnvironment(
    override val clock: PositioningClock,
    settings: TrackerSettings,
) : PositioningRuntimeEnvironment {
    override val platformLocationRequestsEnabled: Boolean = false

    private val settingsRepository = ReplaySettingsRepository(settings)

    lateinit var replayDatabase: AppDatabase
        private set

    override fun database(service: Service): AppDatabase {
        replayDatabase = Room.inMemoryDatabaseBuilder(
            service.applicationContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        return replayDatabase
    }

    override fun locationSessionCoordinator(
        service: Service,
        onSessionError: (Throwable) -> Unit,
    ): LocationSessionGateway = ReplayLocationSessionGateway

    override fun notificationPresenter(service: Service): TrackingNotificationGateway =
        ReplayTrackingNotificationGateway(service)

    override fun runtimeTelemetry(service: Service): RuntimeTelemetry =
        RuntimeTelemetry(service.applicationContext, clock)

    override fun settingsRepository(service: Service): TrackerSettingsRepository = settingsRepository

    override fun recoveryAnchorStore(service: Service): RecoveryAnchorStore =
        RecoveryAnchorStore(service.applicationContext)

    override fun stationaryRegionStore(service: Service): StationaryRegionStore =
        StationaryRegionStore(service.applicationContext)

    override fun queueUploadEngine(
        service: Service,
        database: AppDatabase,
        pushContext: CoroutineContext,
        authenticatedClientProvider: () -> OkHttpClient,
    ): QueueUploadGateway = ReplayQueueUploadGateway

    override fun significantMotionBridge(
        service: Service,
        serviceScope: CoroutineScope,
        onResume: () -> Unit,
    ): SignificantMotionResumeGateway? = null

    val replayActivityHintSource = ReplayActivityHintSource()

    override fun activityHintSource(service: Service): ActivityHintSource = replayActivityHintSource
}

private class ReplaySettingsRepository(
    initialSettings: TrackerSettings,
) : TrackerSettingsRepository {
    private val flow = MutableStateFlow(
        TrackerSettingsState(
            loadState = TrackerSettingsLoadState.Ready,
            settings = initialSettings,
            wasTrackingBeforeExit = false,
            schemaVersion = TrackerSettingsDefaults.schemaVersion,
            revision = 0L,
        )
    )

    override fun isReady(): Boolean = true

    override fun getState(): TrackerSettingsState = flow.value

    override fun observeState(): Flow<TrackerSettingsState> = flow.asStateFlow()

    override fun getSettings(): TrackerSettings = flow.value.settings

    override fun observeSettings(): Flow<TrackerSettings> = flow.map { it.settings }

    override fun dumpDebugState(reason: String) = Unit

    override fun setSendExtendedData(enabled: Boolean) = update { it.copy(sendExtendedData = enabled) }

    override fun setSignificantDataOnly(enabled: Boolean) = update { it.copy(significantDataOnly = enabled) }

    override fun setSparseTracking(enabled: Boolean) = update { it.copy(sparseTracking = enabled) }

    override fun setLowAccuracyFallbackEnabled(enabled: Boolean) =
        update { it.copy(lowAccuracyFallbackEnabled = enabled) }

    override fun setLowAccuracyFallbackTimeoutSec(value: Long) =
        update { it.copy(lowAccuracyFallbackTimeoutSec = value) }

    override fun setStartOnBoot(enabled: Boolean) = update { it.copy(startOnBoot = enabled) }

    override fun setStartTrackingOnLaunch(enabled: Boolean) = update { it.copy(startTrackingOnLaunch = enabled) }

    override fun setKeepScreenOnWhileViewingMap(enabled: Boolean) =
        update { it.copy(keepScreenOnWhileViewingMap = enabled) }

    override fun setGroupModeFitOnlyActiveTrackers(enabled: Boolean) =
        update { it.copy(groupModeFitOnlyActiveTrackers = enabled) }

    override fun wasTrackingBeforeExit(): Boolean = flow.value.wasTrackingBeforeExit

    override fun setWasTrackingBeforeExit(value: Boolean) {
        flow.value = flow.value.copy(wasTrackingBeforeExit = value, revision = flow.value.revision + 1L)
    }

    override fun clearWasTrackingBeforeExit() {
        setWasTrackingBeforeExit(false)
    }

    private fun update(transform: (TrackerSettings) -> TrackerSettings) {
        flow.value = flow.value.copy(
            settings = transform(flow.value.settings),
            revision = flow.value.revision + 1L,
        )
    }
}

private object ReplayLocationSessionGateway : LocationSessionGateway {
    override fun startSession(request: LocationRequest): Boolean = true

    override fun stopSession() = Unit

    override fun isGpsProviderEnabled(): Boolean = true

    override fun isLocationServicesEnabled(): Boolean = true

    override fun getLastLocation(onSuccess: (Location?) -> Unit, onFailure: (Throwable) -> Unit) {
        onSuccess(null)
    }
}

private object ReplayQueueUploadGateway : QueueUploadGateway {
    override suspend fun push(
        scope: QueueUploadScope,
        trackerId: String,
        serverUrl: String,
        config: QueueUploadConfig,
        onBatchUploaded: suspend (visibleSentCount: Int) -> Unit,
    ): QueueUploadResult = QueueUploadResult(failureClass = SyncFailureClass.NONE)
}

private class ReplayTrackingNotificationGateway(
    private val service: Service,
) : TrackingNotificationGateway {
    override fun buildTrackingNotification(snapshot: TrackingRuntimeSnapshot): Notification =
        buildTrackingNotification(
            sentCount = snapshot.pointsSentThisSession,
            queuedCount = snapshot.queuedPointsVisible,
            uiStatus = snapshot.uiStatus,
        )

    override fun buildTrackingNotification(sentCount: Int, queuedCount: Int, uiStatus: TrackingUiStatus): Notification {
        return NotificationCompat.Builder(service, TrackingServiceConstants.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Replay")
            .setContentText("$sentCount sent, $queuedCount queued")
            .build()
    }

    override fun updateForegroundNotification(sentCount: Int, queuedCount: Int, uiStatus: TrackingUiStatus) = Unit

    override fun updateForegroundNotification(snapshot: TrackingRuntimeSnapshot) = Unit
}
