package com.geovault.tracker.di

import android.app.Application
import android.content.Context
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.tracker.settings.TrackerSettingsDataStore
import com.geovault.tracker.data.ApiTrackerManagementRepository
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.RepositoryTrackerBootstrapDataSource
import com.geovault.tracker.data.TrackerBootstrapOrchestrator
import com.geovault.tracker.data.TrackerSessionWarmup
import com.geovault.tracker.data.TrackerDetailRepository
import com.geovault.tracker.data.TrackerDetailRepositoryImpl
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.history.TrackerHistoryRepository
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerSettingsRepositoryImpl
import com.geovault.tracker.settings.TrackerSettingsWritePolicy
import com.geovault.tracker.streaming.LiveStreamBootstrapper
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
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

    private val trackerHistoryRepository by lazy { TrackerHistoryRepository() }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trackerAndGroupManagementRepository by lazy {
        ApiTrackerManagementRepository(appContext, trackerManagementStateStore, ioScope)
    }

    private val trackerDetailRepository by lazy {
        TrackerDetailRepositoryImpl(trackerAndGroupManagementRepository)
    }

    private val trackerBootstrapOrchestrator by lazy {
        TrackerBootstrapOrchestrator(
            dataSource = RepositoryTrackerBootstrapDataSource(
                trackerRepository = trackerAndGroupManagementRepository,
                groupRepository = trackerAndGroupManagementRepository,
            ),
            scope = ioScope,
        )
    }

    private val trackerSessionWarmup by lazy {
        TrackerSessionWarmup(trackerBootstrapOrchestrator)
    }

    /**
     * Process-wide single source of truth for "what should be streaming" / "what is actually
     * streaming" (see [LiveStreamSubscriptionRepository]'s class doc). Seeded from persisted
     * service state on first access so the very first Map/Params reconcile tick after a cold
     * start already knows about a session the service restored via `START_STICKY`, instead of
     * racing it with an empty lease.
     */
    private val liveStreamSubscriptionRepository by lazy {
        LiveStreamSubscriptionRepository(appContext).also { LiveStreamBootstrapper.bootstrap(it) }
    }

    fun initialAuthController(): CommonInitialAuthController = authController

    fun trackerSettingsRepository(): TrackerSettingsRepository = trackerSettingsRepository

    fun trackerManagementRepository(): TrackerManagementRepository = trackerAndGroupManagementRepository

    fun groupManagementRepository(): GroupManagementRepository = trackerAndGroupManagementRepository

    fun trackerManagementStateStore(): TrackerManagementStateStore = trackerManagementStateStore

    fun trackerHistoryRepository(): TrackerHistoryRepository = trackerHistoryRepository

    fun trackerDetailRepository(): TrackerDetailRepository = trackerDetailRepository

    fun trackerBootstrapOrchestrator(): TrackerBootstrapOrchestrator = trackerBootstrapOrchestrator

    fun trackerSessionWarmup(): TrackerSessionWarmup = trackerSessionWarmup

    internal fun liveStreamSubscriptionRepository(): LiveStreamSubscriptionRepository = liveStreamSubscriptionRepository

    companion object {
        @Volatile
        private var instance: TrackerAppServices? = null

        fun from(application: Application): TrackerAppServices {
            return instance ?: synchronized(this) {
                instance ?: TrackerAppServices(application.applicationContext).also { instance = it }
            }
        }
    }
}
