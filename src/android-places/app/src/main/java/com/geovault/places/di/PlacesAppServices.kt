package com.geovault.places.di

import android.app.Application
import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ServerUrlContract
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeovaultAuthServices
import com.geovault.places.data.NavigationTrackingRepository
import com.geovault.places.data.PlacesRepository
import com.geovault.places.data.PlacesStore
import com.geovault.places.domain.ConflictResolutionPolicy
import com.geovault.places.domain.OfflineSyncCoordinator
import com.geovault.places.domain.SyncOfflinePlacesUseCase

class PlacesAppServices private constructor(private val appContext: Context) {
    private val authServices by lazy { GeovaultAuthServices(appContext) }
    private val placesStore by lazy { PlacesStore(appContext) }
    private val placesRepository by lazy { PlacesRepository(appContext) }
    private val navigationRepository by lazy { NavigationTrackingRepository(appContext) }
    private val conflictResolutionPolicy by lazy { ConflictResolutionPolicy() }
    private val syncUseCase by lazy {
        SyncOfflinePlacesUseCase(
            repository = placesRepository,
            cacheStore = placesStore,
            conflictResolutionPolicy = conflictResolutionPolicy,
        )
    }
    private val offlineSyncCoordinator by lazy {
        OfflineSyncCoordinator(
            repository = placesRepository,
            cacheStore = placesStore,
            syncExecutor = syncUseCase,
            navigationRetryFlusher = navigationRepository,
            serverUrlProvider = { GeovaultAuthManager.getServerUrl(appContext) },
        )
    }
    private val authController by lazy {
        CommonInitialAuthController(
            serverConfigService = authServices,
            authSessionService = authServices,
            oauthPreparationService = authServices,
            peerServerUrlsProvider = { ServerUrlContract.getServerUrlsFromOtherApps(appContext) },
        )
    }

    fun placesStore(): PlacesStore = placesStore
    fun cacheStore(): PlacesStore = placesStore
    fun placesRepository(): PlacesRepository = placesRepository
    fun navigationRepository(): NavigationTrackingRepository = navigationRepository
    fun syncOfflinePlacesUseCase(): SyncOfflinePlacesUseCase = syncUseCase
    fun offlineSyncCoordinator(): OfflineSyncCoordinator = offlineSyncCoordinator
    fun initialAuthController(): CommonInitialAuthController = authController

    companion object {
        @Volatile
        private var instance: PlacesAppServices? = null

        fun from(application: Application): PlacesAppServices {
            return instance ?: synchronized(this) {
                instance ?: PlacesAppServices(application.applicationContext).also { instance = it }
            }
        }
    }
}
