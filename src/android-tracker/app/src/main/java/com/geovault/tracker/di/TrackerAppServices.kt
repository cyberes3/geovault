package com.geovault.tracker.di

import android.app.Application
import android.content.Context
import com.geovault.common.ServerUrlContract
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeovaultAuthServices
import com.geovault.tracker.settings.TrackerSettingsDataStore
import com.geovault.tracker.data.ApiTrackerManagementRepository
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.RepositoryTrackerBootstrapDataSource
import com.geovault.tracker.data.TrackerBootstrapOrchestrator
import com.geovault.tracker.data.TrackerSessionBootstrap
import com.geovault.tracker.data.TrackerDetailRepository
import com.geovault.tracker.data.TrackerDetailRepositoryImpl
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.history.TrackerHistoryRepository
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerSettingsRepositoryImpl
import com.geovault.tracker.settings.TrackerSettingsWritePolicy

class TrackerAppServices private constructor(private val appContext: Context) {

    private val authServices by lazy { GeovaultAuthServices(appContext) }

    private val authController by lazy {
        CommonInitialAuthController(
            serverConfigService = authServices,
            authSessionService = authServices,
            oauthPreparationService = authServices,
            peerServerUrlsProvider = { ServerUrlContract.getServerUrlsFromOtherApps(appContext) },
        )
    }

    private val trackerSettingsRepository by lazy {
        TrackerSettingsRepositoryImpl(
            dataStore = TrackerSettingsDataStore(appContext),
            writePolicy = TrackerSettingsWritePolicy()
        )
    }

    private val trackerManagementStateStore by lazy { TrackerManagementStateStore() }

    private val trackerHistoryRepository by lazy { TrackerHistoryRepository() }

    private val trackerAndGroupManagementRepository by lazy {
        ApiTrackerManagementRepository(appContext, trackerManagementStateStore)
    }

    private val trackerDetailRepository by lazy {
        TrackerDetailRepositoryImpl(trackerAndGroupManagementRepository)
    }

    private val trackerBootstrapOrchestrator by lazy {
        TrackerBootstrapOrchestrator(
            dataSource = RepositoryTrackerBootstrapDataSource(
                trackerRepository = trackerAndGroupManagementRepository,
                groupRepository = trackerAndGroupManagementRepository,
            )
        )
    }

    private val trackerSessionBootstrap by lazy {
        TrackerSessionBootstrap(trackerBootstrapOrchestrator)
    }

    fun initialAuthController(): CommonInitialAuthController = authController

    fun trackerSettingsRepository(): TrackerSettingsRepository = trackerSettingsRepository

    fun trackerManagementRepository(): TrackerManagementRepository = trackerAndGroupManagementRepository

    fun groupManagementRepository(): GroupManagementRepository = trackerAndGroupManagementRepository

    fun trackerManagementStateStore(): TrackerManagementStateStore = trackerManagementStateStore

    fun trackerHistoryRepository(): TrackerHistoryRepository = trackerHistoryRepository

    fun trackerDetailRepository(): TrackerDetailRepository = trackerDetailRepository

    fun trackerBootstrapOrchestrator(): TrackerBootstrapOrchestrator = trackerBootstrapOrchestrator

    fun trackerSessionBootstrap(): TrackerSessionBootstrap = trackerSessionBootstrap

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
