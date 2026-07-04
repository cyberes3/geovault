package com.geovault.tracker.positioning

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.positioning.time.PositioningClock
import com.geovault.tracker.replay.runtime.ReplayPositioningClock
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeTelemetryStore
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
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.google.android.gms.location.LocationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * End-to-end proof that flipping the "sparse tracking" setting live, while a session is
 * actively recording, actually changes what gets requested from the platform location
 * provider and how the paused-for-motion stationary ping is scheduled — not just the
 * numbers [com.geovault.tracker.positioning.config.PositioningDensity] and
 * [com.geovault.tracker.positioning.PositioningContext] compute in isolation.
 *
 * This wires a real [PositioningRuntime] through [PositioningRuntime.onCreate] and a real
 * start-tracking pass ([SessionLifecycleSubsystem.performStartTracking]), with
 * [PositioningRuntimeEnvironment.platformLocationRequestsEnabled] left `true` (unlike the
 * capture-replay harness, which disables it) so [LocationRequestSubsystem.applyCurrentLocationRequest]
 * runs for real and hands a [LocationRequest] to a recording [LocationSessionGateway] fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SparseTrackingLiveToggleEndToEndTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private var harness: Harness? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        harness?.close()
        harness = null
        Dispatchers.resetMain()
    }

    @Test
    fun startTracking_normalDensity_appliesBaseWalkingInterval() {
        val h = newHarness()
        h.startTracking()

        assertEquals(
            "the very first GPS request on startup must use the un-scaled WALKING interval",
            NormalIntervalMs,
            h.gateway.appliedRequests.first().intervalMillis,
        )
        assertEquals(
            "once the startup fast-lock burst settles, the steady-state request must return to the normal interval",
            NormalIntervalMs,
            h.gateway.appliedRequests.last().intervalMillis,
        )
    }

    @Test
    fun toggleSparseTrackingOn_whileActivelyTracking_liveDoublesGpsRequestInterval() {
        val h = newHarness()
        h.startTracking()
        assertEquals(NormalIntervalMs, h.gateway.appliedRequests.last().intervalMillis)

        h.setSparseTracking(true)

        assertTrue(
            "toggling sparse tracking on must live-reapply the GPS request instead of leaving the old one in place",
            h.gateway.appliedRequests.size >= 2,
        )
        assertEquals(
            "the reapplied request must double the WALKING interval while sparse tracking is on",
            SparseIntervalMs,
            h.gateway.appliedRequests.last().intervalMillis,
        )
    }

    @Test
    fun toggleSparseTrackingOff_afterEnabling_restoresNormalGpsRequestInterval() {
        val h = newHarness()
        h.startTracking()
        h.setSparseTracking(true)
        assertEquals(SparseIntervalMs, h.gateway.appliedRequests.last().intervalMillis)

        h.setSparseTracking(false)

        assertEquals(
            "turning sparse tracking back off must live-reapply the normal-cadence request",
            NormalIntervalMs,
            h.gateway.appliedRequests.last().intervalMillis,
        )
    }

    @Test
    fun toggleSparseTracking_emitsTelemetryWithDoubledStationaryProbeInterval() {
        val h = newHarness()
        h.startTracking()

        h.setSparseTracking(true)

        val event = h.runtime.deps.runtimeTelemetry.readAllLines()
            .lastOrNull { "|sparse_tracking_changed|" in it }
        assertTrue("expected a sparse_tracking_changed telemetry event after toggling sparse tracking on", event != null)
        assertTrue(
            "expected the doubled stationary probe interval in the sparse_tracking_changed telemetry, got: $event",
            event!!.contains("probeIntervalMs=${StationaryPingController.DEFAULT_INTERVAL_MS * 2}"),
        )
        assertTrue(event.contains("sparse=true"))
    }

    @Test
    fun toggleSparseTrackingOn_whilePausedForMotion_reschedulesStationaryPingWithDoubledInterval() {
        val h = newHarness(significantMotionAvailable = true)
        h.startTracking()
        h.pauseForMotion()

        h.setSparseTracking(true)

        val lines = h.runtime.deps.runtimeTelemetry.readAllLines()
        assertTrue(
            "expected the paused stationary ping timer to be torn down once the sparse interval changed",
            lines.any { "|stationary_ping_reschedule_cancelled|" in it },
        )
        assertTrue(
            "expected the stationary ping to be rescheduled with the doubled sparse interval",
            lines.any {
                "|stationary_ping_scheduled|" in it &&
                    it.contains("intervalMs=${StationaryPingController.DEFAULT_INTERVAL_MS * 2}")
            },
        )
    }

    private fun newHarness(significantMotionAvailable: Boolean = false): Harness {
        val h = Harness(significantMotionAvailable)
        harness = h
        return h
    }

    private companion object {
        private const val NormalIntervalMs = TrackingLocationPolicy.WALKING_INTERVAL_SEC * 1000L
        private const val SparseIntervalMs = NormalIntervalMs * 2
    }
}

/**
 * Builds a real [PositioningRuntime] wired against fakes that make platform side effects
 * (the FLP request, alarms, uploads) observable, while keeping [platformLocationRequestsEnabled]
 * `true` so the sparse-tracking live-toggle path under test actually runs.
 */
private class Harness(significantMotionAvailable: Boolean) : AutoCloseable {
    val gateway = RecordingLocationSessionGateway()
    private val settingsRepository = FakeTrackerSettingsRepository(TrackerSettings(sparseTracking = false))
    private val service: TrackingService
    val runtime: PositioningRuntime
    private lateinit var database: AppDatabase

    init {
        TrackPointCrossSourceState.resetForTests()
        TrackPointPolicyEngine.resetAll()

        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        SelectedTrackerPrefs.setSelectedTracker(
            context = context,
            trackerId = "sparse-toggle-test-tracker",
            trackerName = "Sparse Toggle Test",
        )

        service = Robolectric.buildService(TrackingService::class.java).get()
        RuntimeTelemetryStore.deleteStore(service.applicationContext)

        val environment = object : PositioningRuntimeEnvironment {
            override val clock: PositioningClock = ReplayPositioningClock(
                wallTimeMs = 1_780_000_000_000L,
                elapsedRealtimeNanos = 0L,
            )
            override val platformLocationRequestsEnabled: Boolean = true

            override fun database(service: Service): AppDatabase {
                database = Room.inMemoryDatabaseBuilder(service.applicationContext, AppDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                return database
            }

            override fun locationSessionCoordinator(
                service: Service,
                onSessionError: (Throwable) -> Unit,
            ): LocationSessionGateway = gateway

            override fun notificationPresenter(service: Service): TrackingNotificationGateway =
                FakeTrackingNotificationGateway(service)

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
            ): QueueUploadGateway = FakeQueueUploadGateway

            override fun significantMotionBridge(
                service: Service,
                serviceScope: CoroutineScope,
                onResume: () -> Unit,
            ): SignificantMotionResumeGateway? =
                if (significantMotionAvailable) FakeSignificantMotionBridge() else null
        }

        runtime = PositioningRuntime(ports = PositioningAndroidPorts(service), environment = environment)
        runtime.onCreate()
    }

    fun startTracking() {
        runBlocking { runtime.lifecycle.performStartTracking(trigger = "test_start") }
        // Settle the startup fast-GPS-lock burst (a real, separate feature — see
        // FastGpsLockSubsystem) so cadence assertions exercise the steady-state request
        // that sparse tracking actually scales, not the one-shot fast-lock probe request.
        runtime.recovery.fastLock.stopFastGpsLockWindow(reason = "test_settle")
    }

    fun pauseForMotion() {
        runtime.collection.pauseGpsInternal(force = true)
    }

    fun setSparseTracking(enabled: Boolean) {
        settingsRepository.setSparseTracking(enabled)
    }

    override fun close() {
        if (runtime.state.isTracking) {
            runtime.lifecycle.stopTracking(reason = "test_complete")
        }
        runtime.onDestroy()
        if (::database.isInitialized) {
            database.close()
        }
    }
}

private class RecordingLocationSessionGateway : LocationSessionGateway {
    val appliedRequests = mutableListOf<LocationRequest>()

    override fun startSession(request: LocationRequest): Boolean {
        appliedRequests += request
        return true
    }

    override fun stopSession() = Unit

    override fun isGpsProviderEnabled(): Boolean = true

    override fun isLocationServicesEnabled(): Boolean = true

    override fun getLastLocation(onSuccess: (Location?) -> Unit, onFailure: (Throwable) -> Unit) {
        onSuccess(null)
    }
}

private class FakeSignificantMotionBridge : SignificantMotionResumeGateway {
    override fun request() = Unit
    override fun cancel() = Unit
    override fun isAvailable(): Boolean = true
}

private object FakeQueueUploadGateway : QueueUploadGateway {
    override suspend fun push(
        scope: QueueUploadScope,
        trackerId: String,
        serverUrl: String,
        config: QueueUploadConfig,
        onBatchUploaded: suspend (visibleSentCount: Int) -> Unit,
    ): QueueUploadResult = QueueUploadResult(failureClass = SyncFailureClass.NONE)
}

private class FakeTrackingNotificationGateway(private val service: Service) : TrackingNotificationGateway {
    override fun buildTrackingNotification(snapshot: TrackingRuntimeSnapshot): Notification =
        buildTrackingNotification(
            sentCount = snapshot.pointsSentThisSession,
            queuedCount = snapshot.queuedPointsVisible,
            uiStatus = snapshot.uiStatus,
        )

    override fun buildTrackingNotification(sentCount: Int, queuedCount: Int, uiStatus: TrackingUiStatus): Notification {
        return androidx.core.app.NotificationCompat.Builder(service, TrackingServiceConstants.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sparse Toggle Test")
            .setContentText("$sentCount sent, $queuedCount queued")
            .build()
    }

    override fun updateForegroundNotification(sentCount: Int, queuedCount: Int, uiStatus: TrackingUiStatus) = Unit

    override fun updateForegroundNotification(snapshot: TrackingRuntimeSnapshot) = Unit
}

private class FakeTrackerSettingsRepository(
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
