package com.geovault.tracker.di

import android.app.Application
import android.content.Context
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.tracker.settings.TrackerSettingsDataStore
import com.geovault.tracker.data.ApiTrackerManagementRepository
import com.geovault.tracker.data.CatalogSelectionController
import com.geovault.tracker.data.CatalogStateStore
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.MutationQueue
import com.geovault.tracker.data.RepositoryTrackerBootstrapDataSource
import com.geovault.tracker.data.CatalogBootstrap
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.history.HistoryResetPort
import com.geovault.tracker.history.HistoryTrunkIngestor
import com.geovault.tracker.history.TrackerHistoryIntentDispatcher
import com.geovault.tracker.history.TrackerHistoryRepository
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerSettingsRepositoryImpl
import com.geovault.tracker.settings.TrackerSettingsWritePolicy
import com.geovault.tracker.policy.AdmissionPipeline
import com.geovault.tracker.runtime.AccountReset
import com.geovault.tracker.runtime.TrackerRuntimeEngine
import com.geovault.tracker.runtime.TrackerRuntimeStore
import com.geovault.tracker.streaming.ClearReason
import com.geovault.tracker.streaming.LiveStreamBootstrapper
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import com.geovault.tracker.ui.TrackerUiEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TrackerAppServices private constructor(private val appContext: Context) {

    fun authSession(): GeoVaultAuthSession = GeoVaultAuthSession.get()

    private val authController by lazy {
        CommonInitialAuthController.standard(authSession(), appContext)
    }

    private val trackerSettingsRepository by lazy {
        TrackerSettingsRepositoryImpl(
            dataStore = TrackerSettingsDataStore(appContext),
            writePolicy = TrackerSettingsWritePolicy()
        )
    }

    private val trackerManagementStateStore by lazy { TrackerManagementStateStore() }

    private val catalogStateStore by lazy { CatalogStateStore(trackerManagementStateStore) }

    private val catalogSelectionController by lazy { CatalogSelectionController(catalogStateStore) }

    private val mutationQueue by lazy { MutationQueue() }

    private val uiEffects by lazy { TrackerUiEffects() }

    private val trackerHistoryRepository by lazy { TrackerHistoryRepository() }

    private val historyIntentDispatcher by lazy { TrackerHistoryIntentDispatcher(trackerHistoryRepository) }

    private val historyTrunkIngestor by lazy {
        HistoryTrunkIngestor(historyIntentDispatcher, trackerHistoryRepository)
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trackerAndGroupManagementRepository by lazy {
        ApiTrackerManagementRepository(appContext, catalogStateStore, ioScope)
    }

    private val catalogBootstrap by lazy {
        CatalogBootstrap(
            dataSource = RepositoryTrackerBootstrapDataSource(
                trackerRepository = trackerAndGroupManagementRepository,
                groupRepository = trackerAndGroupManagementRepository,
            ),
            scope = ioScope,
            catalogTrackers = { catalogStateStore.trackers.value },
            fetchCatalogGeometry = { trackers ->
                historyTrunkIngestor.fetchCatalog(
                    catalogTrackers = trackers,
                    loadGeometry = { requested ->
                        if (requested.size == 1) {
                            listOf(trackerAndGroupManagementRepository.loadTrackerGeometry(requested.single()))
                        } else {
                            trackerAndGroupManagementRepository.loadTrackersGeometry(requested)
                        }
                    },
                    activeSessionStartMsFor = { trackerId ->
                        val runtime = TrackerRuntimeStore.value.recording
                        runtime.sessionStartTimeMs.takeIf {
                            runtime.localRecordingActive && it > 0L &&
                                runtime.locallyRecordedTrackerId.trim() == trackerId
                        }
                    },
                )
            },
        )
    }

    /**
     * Process-wide single source of truth for "what should be streaming" / "what is actually
     * streaming" (see [LiveStreamSubscriptionRepository]'s class doc). Seeded from persisted
     * service state on first access so the very first Map/Params reconcile tick after a cold
     * start already knows about a session the service restored via `START_STICKY`, instead of
     * racing it with an empty lease.
     */
    private val admissionPipeline by lazy { AdmissionPipeline() }

    fun admissionPipeline(): AdmissionPipeline = admissionPipeline

    private val liveStreamSubscriptionRepository by lazy {
        LiveStreamSubscriptionRepository(appContext).also { LiveStreamBootstrapper.bootstrap(it) }
    }

    fun initialAuthController(): CommonInitialAuthController = authController

    fun trackerSettingsRepository(): TrackerSettingsRepository = trackerSettingsRepository

    fun trackerManagementRepository(): TrackerManagementRepository = trackerAndGroupManagementRepository

    fun groupManagementRepository(): GroupManagementRepository = trackerAndGroupManagementRepository

    fun trackerManagementStateStore(): TrackerManagementStateStore = trackerManagementStateStore

    fun catalogStateStore(): CatalogStateStore = catalogStateStore

    fun catalogSelectionController(): CatalogSelectionController = catalogSelectionController

    fun clearSelectedTrackerAndInvalidateCaches(context: Context) {
        catalogSelectionController.clearSelectedTracker(context)
        trackerManagementRepository().clearSelectedTrackerCaches()
    }

    fun mutationQueue(): MutationQueue = mutationQueue

    fun uiEffects(): TrackerUiEffects = uiEffects

    fun historyTrunkIngestor(): HistoryTrunkIngestor = historyTrunkIngestor

    fun historyResetPort(): HistoryResetPort = HistoryResetPort { trackerHistoryRepository.reset() }

    fun trackerHistoryRepository(): TrackerHistoryRepository = trackerHistoryRepository

    fun catalogBootstrap(): CatalogBootstrap = catalogBootstrap

    internal fun liveStreamSubscriptionRepository(): LiveStreamSubscriptionRepository = liveStreamSubscriptionRepository

    fun runtimeEngine(): TrackerRuntimeEngine {
        return TrackerRuntimeEngine.get(appContext)
    }

    fun accountReset(): AccountReset {
        return accountReset
    }

    private val accountReset by lazy {
        AccountReset(
            context = appContext,
            settingsRepository = trackerSettingsRepository,
        ).also { reset ->
            reset.registerSlice {
                liveStreamSubscriptionRepository.clearAllLeases(ClearReason.LOGOUT)
            }
            reset.registerSlice { context ->
                catalogSelectionController.clearSelectedTracker(context)
            }
            reset.registerSlice {
                catalogStateStore.clearAll()
                trackerManagementStateStore.clearAll()
            }
            reset.registerSlice {
                historyResetPort().reset()
            }
            reset.registerAfterPersistSlice {
                catalogBootstrap.resetLaunchState()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: TrackerAppServices? = null

        fun from(application: Application): TrackerAppServices {
            return instance ?: synchronized(this) {
                instance ?: TrackerAppServices(application.applicationContext).also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
