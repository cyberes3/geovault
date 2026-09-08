package com.geovault.places.data

import android.content.Context
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
import com.geovault.common.net.awaitResponse
import com.geovault.common.settings.GeoVaultCachedDocumentStore
import com.geovault.common.sync.GeoVaultHttpFailureClassifier
import com.geovault.common.sync.GeoVaultHttpFailureKind
import com.geovault.places.domain.NavigationRetryFlusher
import com.geovault.places.model.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class NavigationTrackingRepository(
    context: Context,
    private val session: GeoVaultAuthSession = GeoVaultAuthSession.get(),
    private val apiCache: GeoVaultHttp.CachedApiHolder<PlacesApi> = GeoVaultHttp.CachedApiHolder(),
) : NavigationRetryFlusher {
    private val cached = GeoVaultCachedDocumentStore(
        context = context.applicationContext,
        fileName = PlacesNavDocument.FILE_NAME,
        documentSerializer = PlacesNavDocument.serializer(),
        defaultValue = PlacesNavDocument(),
        currentVersion = PlacesNavDocument.SCHEMA_VERSION,
        legacyMapper = PlacesNavDocument::fromLegacy,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun preloadOnLaunch() {
        cached.preloadBlocking()
    }

    fun track(place: Place) {
        val id = place.serverId ?: return
        scope.launch { trackId(id) }
    }

    override suspend fun flushPending() {
        val ids = cached.get().pendingNavigationIds
        ids.forEach { id ->
            runCatching { trackId(id) }
        }
    }

    fun clearPendingBlocking() {
        runBlocking {
            cached.update { PlacesNavDocument() }
        }
    }

    private suspend fun trackId(id: Int) {
        val api = apiOrNull() ?: run {
            addPending(id)
            return
        }
        try {
            val response = api.trackNavigation(id).awaitResponse("trackNavigation")
            if (response.isSuccessful) {
                removePending(id)
                return
            }
            if (shouldDropPending(response.code())) {
                removePending(id)
            } else {
                addPending(id)
            }
        } catch (_: Throwable) {
            addPending(id)
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

    private suspend fun addPending(id: Int) {
        cached.update { doc ->
            if (id in doc.pendingNavigationIds) doc else doc.copy(pendingNavigationIds = doc.pendingNavigationIds + id)
        }
    }

    private suspend fun removePending(id: Int) {
        cached.update { doc ->
            doc.copy(pendingNavigationIds = doc.pendingNavigationIds.filterNot { it == id })
        }
    }

    private fun apiOrNull(): PlacesApi? {
        val url = session.getServerUrl()
        if (url.isBlank()) return null
        val parsed = GeoVaultServerUrl.parse(url) ?: return null
        return GeoVaultHttp.createCachedApi(parsed, PlacesApi::class.java, apiCache)
    }
}
