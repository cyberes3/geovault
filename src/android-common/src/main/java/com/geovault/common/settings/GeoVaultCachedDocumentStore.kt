package com.geovault.common.settings

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer

/**
 * In-memory cache over [GeoVaultDocumentStore] with a ready gate for bootstrap.
 */
class GeoVaultCachedDocumentStore<T>(
    context: Context,
    fileName: String,
    documentSerializer: KSerializer<T>,
    defaultValue: T,
    currentVersion: Int,
    migrations: List<GeoVaultDocumentMigration> = emptyList(),
    legacyFileName: String? = null,
    legacyMapper: ((GeoVaultLegacySettingsBlob) -> T)? = null,
) {
    private val store = GeoVaultDocumentStore(
        context = context,
        fileName = fileName,
        documentSerializer = documentSerializer,
        defaultValue = defaultValue,
        currentVersion = currentVersion,
        migrations = migrations,
        legacyFileName = legacyFileName,
        legacyMapper = legacyMapper,
    )
    private val _data = MutableStateFlow(defaultValue)
    val data: StateFlow<T> = _data.asStateFlow()

    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            store.data.collect { document ->
                _data.value = document
                if (!ready.isCompleted) {
                    ready.complete(Unit)
                }
            }
        }
    }

    suspend fun awaitReady() {
        ready.await()
    }

    fun preloadBlocking() {
        runBlocking(Dispatchers.IO) {
            awaitReady()
        }
    }

    suspend fun get(): T {
        awaitReady()
        return _data.value
    }

    suspend fun update(transform: (T) -> T) {
        awaitReady()
        store.update(transform)
        _data.value = store.get()
    }
}
