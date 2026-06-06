package com.geovault.tracker.positioning

import android.app.Service
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.positioning.time.PositioningClock
import com.geovault.tracker.positioning.time.SystemPositioningClock
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeGateway
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.LocationSessionGateway
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadGateway
import com.geovault.tracker.services.TrackingNotificationGateway
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.di.TrackerAppServices
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import okhttp3.OkHttpClient

internal interface PositioningRuntimeEnvironment {
    val clock: PositioningClock
    val platformLocationRequestsEnabled: Boolean

    fun database(service: Service): AppDatabase

    fun locationSessionCoordinator(
        service: Service,
        onSessionError: (Throwable) -> Unit,
    ): LocationSessionGateway

    fun notificationPresenter(service: Service): TrackingNotificationGateway

    fun runtimeTelemetry(service: Service): RuntimeTelemetry

    fun settingsRepository(service: Service): TrackerSettingsRepository

    fun recoveryAnchorStore(service: Service): RecoveryAnchorStore

    fun stationaryRegionStore(service: Service): StationaryRegionStore

    fun queueUploadEngine(
        service: Service,
        database: AppDatabase,
        pushContext: CoroutineContext,
        authenticatedClientProvider: () -> OkHttpClient,
    ): QueueUploadGateway

    fun significantMotionBridge(
        service: Service,
        serviceScope: CoroutineScope,
        onResume: () -> Unit,
    ): SignificantMotionResumeGateway?
}

internal object ProductionPositioningRuntimeEnvironment : PositioningRuntimeEnvironment {
    override val clock: PositioningClock = SystemPositioningClock
    override val platformLocationRequestsEnabled: Boolean = true

    override fun database(service: Service): AppDatabase = AppDatabase.getDatabase(service)

    override fun locationSessionCoordinator(
        service: Service,
        onSessionError: (Throwable) -> Unit,
    ): LocationSessionGateway = LocationSessionCoordinator(service, onSessionError)

    override fun notificationPresenter(service: Service): TrackingNotificationGateway =
        TrackingNotificationPresenter(service)

    override fun runtimeTelemetry(service: Service): RuntimeTelemetry =
        RuntimeTelemetry(service.applicationContext, clock)

    override fun settingsRepository(service: Service): TrackerSettingsRepository =
        TrackerAppServices.from(service.application).trackerSettingsRepository()

    override fun recoveryAnchorStore(service: Service): RecoveryAnchorStore =
        RecoveryAnchorStore(service.applicationContext)

    override fun stationaryRegionStore(service: Service): StationaryRegionStore =
        StationaryRegionStore(service.applicationContext)

    override fun queueUploadEngine(
        service: Service,
        database: AppDatabase,
        pushContext: CoroutineContext,
        authenticatedClientProvider: () -> OkHttpClient,
    ): QueueUploadGateway = QueueUploadEngine(
        context = service.applicationContext,
        locationDao = database.locationDao(),
        pushContext = pushContext,
        authenticatedClientProvider = authenticatedClientProvider,
    )

    override fun significantMotionBridge(
        service: Service,
        serviceScope: CoroutineScope,
        onResume: () -> Unit,
    ): SignificantMotionResumeGateway = SignificantMotionResumeBridge(
        trigger = SensorManagerSignificantMotionTrigger(service.applicationContext),
        onResume = onResume,
    )
}
