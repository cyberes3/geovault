package com.geovault.places.data

import android.content.Context
import com.geovault.common.geo.external.GeoVaultExternalMapLauncher
import com.geovault.common.settings.GeoVaultDocumentStore
import com.geovault.common.sync.GeoVaultHttpFailureClassifier
import com.geovault.common.sync.GeoVaultHttpFailureKind
import com.geovault.places.domain.NavigationRetryFlusher
import com.geovault.places.model.Feature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class NavigationTrackingRepository(private val context: Context) : NavigationRetryFlusher {
    private val store = GeoVaultDocumentStore(
        context = context,
        fileName = PlacesNavDocument.FILE_NAME,
        documentSerializer = PlacesNavDocument.serializer(),
        defaultValue = PlacesNavDocument(),
        currentVersion = PlacesNavDocument.SCHEMA_VERSION,
        legacyMapper = PlacesNavDocument::fromLegacy,
    )
    private val pendingLock = Any()
    private var pendingIds: List<Int> = emptyList()

    @Volatile
    private var loaded = false

    fun preloadOnLaunch() {
        ensureLoaded()
    }

    fun openInGoogleMaps(
        context: Context,
        feature: Feature,
        onUnavailable: () -> Unit,
    ): Boolean {
        val coords = feature.geometry.coordinates
        if (coords.size < 2) return false
        return GeoVaultExternalMapLauncher.open(
            context = context,
            latitude = coords[1],
            longitude = coords[0],
            label = feature.properties.name,
            onUnavailable = onUnavailable,
        )
    }

    fun trackNavigation(feature: Feature, serverUrl: String) {
        val dbId = feature.properties.database_id ?: return
        if (serverUrl.isBlank()) return
        val api = PlacesApiFactory.create(serverUrl)
        flushPending(serverUrl)
        api.trackNavigation(dbId).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) return
                if (shouldDropPending(response.code())) {
                    removePending(dbId)
                    return
                }
                addPending(dbId)
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                addPending(dbId)
            }
        })
    }

    override fun flushPending(serverUrl: String) {
        if (serverUrl.isBlank()) return
        val api = PlacesApiFactory.create(serverUrl)
        getPending().forEach { id ->
            api.trackNavigation(id).enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {
                    if (response.isSuccessful) {
                        removePending(id)
                        return
                    }
                    if (shouldDropPending(response.code())) {
                        removePending(id)
                    }
                }

                override fun onFailure(call: Call<Void>, t: Throwable) = Unit
            })
        }
    }

    fun clearPending() {
        synchronized(pendingLock) {
            ensureLoadedLocked()
            pendingIds = emptyList()
            persistLocked()
        }
    }

    private fun shouldDropPending(httpCode: Int): Boolean {
        return when (GeoVaultHttpFailureClassifier.classify(httpCode, null, null)) {
            GeoVaultHttpFailureKind.NotFound,
            GeoVaultHttpFailureKind.Auth,
            GeoVaultHttpFailureKind.PermanentClient -> true
            else -> false
        }
    }

    private fun getPending(): List<Int> {
        return synchronized(pendingLock) {
            ensureLoadedLocked()
            pendingIds
        }
    }

    private fun addPending(id: Int) {
        synchronized(pendingLock) {
            ensureLoadedLocked()
            if (id !in pendingIds) {
                pendingIds = pendingIds + id
                persistLocked()
            }
        }
    }

    private fun removePending(id: Int) {
        synchronized(pendingLock) {
            ensureLoadedLocked()
            pendingIds = pendingIds.filterNot { it == id }
            persistLocked()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(pendingLock) {
            ensureLoadedLocked()
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        pendingIds = runBlocking(Dispatchers.IO) { store.get() }.pendingNavigationIds
        loaded = true
    }

    private fun persistLocked() {
        val ids = pendingIds
        runBlocking(Dispatchers.IO) {
            store.update { PlacesNavDocument(pendingNavigationIds = ids) }
        }
    }
}
