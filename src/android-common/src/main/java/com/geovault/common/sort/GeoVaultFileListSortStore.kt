package com.geovault.common.sort

import android.content.Context
import com.geovault.common.settings.GeoVaultPrefsStore
import com.geovault.common.settings.PrefKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GeoVaultFileListSortStore private constructor(context: Context) {
    private val store = GeoVaultPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        schemaVersion = SCHEMA_VERSION,
        registeredKeys = ALL_KEYS,
    )

    fun preloadAllDataBlocking(): Boolean = store.preloadAllDataBlocking()

    fun observe(scope: GeoVaultFileListSortScope): Flow<GeoVaultFileListSortMode> =
        store.observe(keyFor(scope)).map(GeoVaultFileListSortMode::fromStored)

    fun getBlocking(scope: GeoVaultFileListSortScope): GeoVaultFileListSortMode =
        GeoVaultFileListSortMode.fromStored(store.getBlocking(keyFor(scope)))

    suspend fun put(scope: GeoVaultFileListSortScope, mode: GeoVaultFileListSortMode) {
        store.put(keyFor(scope), mode.name)
    }

    fun putBlocking(scope: GeoVaultFileListSortScope, mode: GeoVaultFileListSortMode) {
        store.putBlocking(keyFor(scope), mode.name)
    }

    private fun keyFor(scope: GeoVaultFileListSortScope): PrefKey.StringKey = when (scope) {
        GeoVaultFileListSortScope.DATA_FILES -> KEY_DATA_FILES_SORT
        GeoVaultFileListSortScope.COORDINATE_SYSTEMS -> KEY_COORDINATE_SYSTEMS_SORT
    }

    companion object {
        private const val PREFS_NAME = "geovault_file_list_sort"
        private const val SCHEMA_VERSION = 1

        private val KEY_DATA_FILES_SORT = PrefKey.StringKey(
            name = "data_files_sort",
            defaultValue = GeoVaultFileListSortMode.DEFAULT.name,
        )
        private val KEY_COORDINATE_SYSTEMS_SORT = PrefKey.StringKey(
            name = "coordinate_systems_sort",
            defaultValue = GeoVaultFileListSortMode.DEFAULT.name,
        )

        private val ALL_KEYS: Set<PrefKey<*>> = setOf(
            KEY_DATA_FILES_SORT,
            KEY_COORDINATE_SYSTEMS_SORT,
        )

        @Volatile
        private var instance: GeoVaultFileListSortStore? = null

        fun getInstance(context: Context): GeoVaultFileListSortStore {
            return instance ?: synchronized(this) {
                instance ?: GeoVaultFileListSortStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
