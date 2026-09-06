package com.geovault.common.sort

import android.content.Context
import com.geovault.common.settings.FileListSortDocument
import com.geovault.common.settings.GeoVaultDocumentStore
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

    fun observe(scope: GeoVaultFileListSortScope): Flow<GeoVaultFileListSortMode> {
        return store.data
            .map { document -> modeFor(document, scope) }
            .distinctUntilChanged()
    }

    fun getBlocking(scope: GeoVaultFileListSortScope): GeoVaultFileListSortMode {
        return runBlocking(Dispatchers.IO) {
            modeFor(store.get(), scope)
        }
    }

    suspend fun put(scope: GeoVaultFileListSortScope, mode: GeoVaultFileListSortMode) {
        store.update { current ->
            when (scope) {
                GeoVaultFileListSortScope.DATA_FILES -> current.copy(dataFilesSort = mode.name)
                GeoVaultFileListSortScope.COORDINATE_SYSTEMS -> current.copy(coordinateSystemsSort = mode.name)
            }
        }
    }

    fun putBlocking(scope: GeoVaultFileListSortScope, mode: GeoVaultFileListSortMode) {
        runBlocking(Dispatchers.IO) { put(scope, mode) }
    }

    private fun modeFor(document: FileListSortDocument, scope: GeoVaultFileListSortScope): GeoVaultFileListSortMode {
        val stored = when (scope) {
            GeoVaultFileListSortScope.DATA_FILES -> document.dataFilesSort
            GeoVaultFileListSortScope.COORDINATE_SYSTEMS -> document.coordinateSystemsSort
        }
        return GeoVaultFileListSortMode.fromStored(stored)
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
