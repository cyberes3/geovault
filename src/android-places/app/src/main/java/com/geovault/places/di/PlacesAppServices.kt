package com.geovault.places.di

import android.app.Application
import android.content.Context
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.places.PlacesApplication
import com.geovault.places.data.NavigationTrackingRepository
import com.geovault.places.data.PlacesRepository
import com.geovault.places.data.PlacesStore
import com.geovault.places.domain.ConflictResolutionPolicy
import com.geovault.places.domain.DeletePlaceUseCase
import com.geovault.places.domain.PlacesSyncEngine
import com.geovault.places.domain.SavePlaceUseCase

class PlacesAppServices(appContext: Context) {
    private val app = appContext.applicationContext

    fun authSession(): GeoVaultAuthSession = GeoVaultAuthSession.get()

    private val placesStore by lazy { PlacesStore(app) }
    private val placesRepository by lazy { PlacesRepository(authSession()) }
    private val navigationRepository by lazy { NavigationTrackingRepository(app, authSession()) }
    private val conflictResolutionPolicy by lazy { ConflictResolutionPolicy() }
    private val savePlaceUseCase by lazy { SavePlaceUseCase(placesRepository, placesStore) }
    private val deletePlaceUseCase by lazy { DeletePlaceUseCase(placesRepository, placesStore) }
    private val syncEngine by lazy {
        PlacesSyncEngine(
            remote = placesRepository,
            store = placesStore,
            conflictResolutionPolicy = conflictResolutionPolicy,
            navigationFlusher = navigationRepository,
        )
    }
    private val authController by lazy {
        CommonInitialAuthController.standard(authSession(), app)
    }

    fun placesStore(): PlacesStore = placesStore
    fun placesRepository(): PlacesRepository = placesRepository
    fun navigationRepository(): NavigationTrackingRepository = navigationRepository
    fun savePlaceUseCase(): SavePlaceUseCase = savePlaceUseCase
    fun deletePlaceUseCase(): DeletePlaceUseCase = deletePlaceUseCase
    fun syncEngine(): PlacesSyncEngine = syncEngine
    fun initialAuthController(): CommonInitialAuthController = authController

    companion object {
        fun from(application: Application): PlacesAppServices {
            return (application as PlacesApplication).services
        }
    }
}
