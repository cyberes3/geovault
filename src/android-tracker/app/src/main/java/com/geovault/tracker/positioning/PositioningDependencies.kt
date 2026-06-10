package com.geovault.tracker.positioning

import android.app.Service
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.ingest.TrackerLocationPipeline
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.sensor.ImuMotionClassifier
import com.geovault.tracker.sensor.SignificantMotionResumeGateway
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationSessionGateway
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.QueueUploadGateway
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.TrackingNotificationGateway
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

internal class PositioningDependencies(
    private val runtime: PositioningRuntime,
    private val service: Service,
    val serviceScope: CoroutineScope,
    private val environment: PositioningRuntimeEnvironment = ProductionPositioningRuntimeEnvironment,
) {
    val clock = environment.clock

    lateinit var database: AppDatabase
        private set
    lateinit var settingsRepository: TrackerSettingsRepository
        private set
    lateinit var sessionCoordinator: TrackingSessionCoordinator
        private set
    lateinit var locationIngestCoordinator: LocationIngestCoordinator
        private set
    lateinit var trackerLocationPipeline: TrackerLocationPipeline
        private set
    lateinit var notificationPresenter: TrackingNotificationGateway
        private set
    lateinit var runtimeEventPublisher: RuntimeEventPublisher
        private set
    lateinit var queueUploadEngine: QueueUploadGateway
        private set
    lateinit var locationSessionCoordinator: LocationSessionGateway
        private set
    lateinit var runtimeTelemetry: RuntimeTelemetry
        private set
    lateinit var recoveryAnchorStore: RecoveryAnchorStore
        private set
    lateinit var stationaryPingController: StationaryPingController
        private set
    lateinit var stationaryFreshnessCoordinator: StationaryFreshnessCoordinator
        private set

    var httpClient: OkHttpClient? = null
    var significantMotionBridge: SignificantMotionResumeGateway? = null
    var imuMotionClassifier: ImuMotionClassifier? = null

    val lowAccuracyFallbackCoordinator = LowAccuracyFallbackCoordinator {
        runtime.contextBuilder.currentPositioningRecoveryConfig()
    }
    val repeatedOutlierSuppressor = RepeatedOutlierSuppressor {
        runtime.contextBuilder.currentPositioningRecoveryConfig()
    }
    val freshnessRecoveryController = FreshnessRecoveryController()
    val providerHealthController = ProviderHealthController(
        staleFixDeliveryMs = TrackingServiceConstants.FIX_DELIVERY_STALE_MS,
    )
    val pointFreshnessTracker = PointFreshnessTracker()
    val autoTrackingMotionEngine = AutoTrackingMotionEngine()
    val autoTrackingMotionCoordinator = AutoTrackingMotionCoordinator(
        engine = autoTrackingMotionEngine,
        evidenceGate = AutoTrackingMotionEvidenceGate(),
        streakPreserveWindowMs = TrackingServiceConstants.AUTO_MOTION_CAP_EVIDENCE_STREAK_PRESERVE_WINDOW_MS,
    )

    fun wire(settingsRepository: TrackerSettingsRepository) {
        this.settingsRepository = settingsRepository
        database = environment.database(service)
        sessionCoordinator = TrackingSessionCoordinator()
        notificationPresenter = environment.notificationPresenter(service)
        runtimeEventPublisher = RuntimeEventPublisher(service.applicationContext)
        runtimeTelemetry = environment.runtimeTelemetry(service)
        recoveryAnchorStore = environment.recoveryAnchorStore(service)
        locationSessionCoordinator = environment.locationSessionCoordinator(service) { error ->
            runtimeTelemetry.event(
                "location_request_registration_failed",
                "type=${error.javaClass.simpleName} message=${error.message.orEmpty()}",
            )
            runtime.locationRequests.scheduleLocationRequestReapplyRetry(reason = "async_registration_failure")
        }
        locationIngestCoordinator = LocationIngestCoordinator(database.locationDao()) { event ->
            runtimeTelemetry.event(
                name = "local_stall_reanchor",
                details = "stream=${event.streamKey} reason=${event.policyReason ?: "unknown"} " +
                    "streak=${event.rejectStreak} anchorAgeMs=${event.anchorAgeMs}",
            )
        }
        trackerLocationPipeline = TrackerLocationPipeline(
            locationIngestCoordinator = locationIngestCoordinator,
            freshnessRecoveryController = freshnessRecoveryController,
            repeatedOutlierSuppressor = repeatedOutlierSuppressor,
        )
        queueUploadEngine = environment.queueUploadEngine(
            service = service,
            database = database,
            pushContext = runtime.pushDispatcher,
            authenticatedClientProvider = { runtime.upload.getAuthenticatedHttpClient() },
        )
        significantMotionBridge = environment.significantMotionBridge(
            service = service,
            serviceScope = serviceScope,
            onResume = { runtime.collection.onSignificantMotion() },
        )
        imuMotionClassifier = ImuMotionClassifier(
            context = service.applicationContext,
            onClassification = { ctx -> runtime.motion.onImuMotionUpdate(ctx) },
        )
        val initialProbeIntervalMs = PositioningDensity.from(settingsRepository.getSettings())
            .scaleDurationMs(StationaryPingController.DEFAULT_INTERVAL_MS)
        stationaryPingController = StationaryPingController(
            scope = serviceScope,
            initialIntervalMs = initialProbeIntervalMs,
            actions = object : StationaryPingActions {
                override fun requestProbe(reason: String) {
                    runtime.recovery.pausedFreshness.requestStationaryFreshnessProbe(reason = reason)
                }

                override fun logEvent(name: String, details: String) {
                    runtimeTelemetry.event(name, details)
                }
            },
        )
        stationaryFreshnessCoordinator = StationaryFreshnessCoordinator(
            store = environment.stationaryRegionStore(service),
            pingController = stationaryPingController,
            scope = serviceScope,
            actions = object : StationaryFreshnessActions {
                override fun logEvent(name: String, details: String) {
                    runtimeTelemetry.event(name, details)
                }

                override fun onProbeTimeout() {
                    runtime.collection.pauseGpsInternal(force = true)
                }
            },
        )
    }
}
