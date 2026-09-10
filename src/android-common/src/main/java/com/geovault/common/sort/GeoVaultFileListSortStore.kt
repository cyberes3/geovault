package com.geovault.common.sort

import android.content.Context
import com.geovault.common.settings.FileListSortDocument
import com.geovault.common.settings.FileListSortV1ToV2Migration
import com.geovault.common.settings.GeoVaultDocumentStore
import com.geovault.common.settings.modeName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

class GeoVaultFileListSortStore private constructor(context: Context) {
    private val store = GeoVaultDocumentStore(
        context = context,
        fileName = FileListSortDocument.FILE_NAME,
        documentSerializer = FileListSortDocument.serializer(),
        defaultValue = FileListSortDocument(),
        currentVersion = FileListSortDocument.SCHEMA_VERSION,
        migrations = listOf(FileListSortV1ToV2Migration),
        legacyMapper = FileListSortDocument::fromLegacy,
    )

    fun preloadAllDataBlocking(): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                store.get()
            }
            true
        }.getOrElse {
            false
        }
    }

    fun observe(scope: GeoVaultFileListSortScope): Flow<GeoVaultFileListSortMode> = observe(scope.key)

    fun observe(scopeKey: String): Flow<GeoVaultFileListSortMode> {
        return store.data
            .map { document -> modeFor(document, scopeKey) }
            .distinctUntilChanged()
    }

    fun getBlocking(scope: GeoVaultFileListSortScope): GeoVaultFileListSortMode = getBlocking(scope.key)

    fun getBlocking(scopeKey: String): GeoVaultFileListSortMode {
        return runBlocking(Dispatchers.IO) {
            modeFor(store.get(), scopeKey)
        }
    }

    suspend fun put(scope: GeoVaultFileListSortScope, mode: GeoVaultFileListSortMode) {
        put(scope.key, mode)
    }

    suspend fun put(scopeKey: String, mode: GeoVaultFileListSortMode) {
        store.update { current ->
            current.copy(modesByScope = current.modesByScope + (scopeKey to mode.name))
        }
    }

    fun putBlocking(scope: GeoVaultFileListSortScope, mode: GeoVaultFileListSortMode) {
        putBlocking(scope.key, mode)
    }

    fun putBlocking(scopeKey: String, mode: GeoVaultFileListSortMode) {
        runBlocking(Dispatchers.IO) { put(scopeKey, mode) }
    }

    private fun modeFor(document: FileListSortDocument, scopeKey: String): GeoVaultFileListSortMode {
        return GeoVaultFileListSortMode.fromStored(document.modeName(scopeKey))
    }

    companion object {
        @Volatile
        private var instance: GeoVaultFileListSortStore? = null

        fun getInstance(context: Context): GeoVaultFileListSortStore {
            return instance ?: synchronized(this) {
                instance ?: GeoVaultFileListSortStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
