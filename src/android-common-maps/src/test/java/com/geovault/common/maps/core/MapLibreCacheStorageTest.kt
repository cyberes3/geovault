package com.geovault.common.maps.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MapLibreCacheStorageTest {

    private val appContext get() = RuntimeEnvironment.getApplication()

    @Test
    fun resourceCacheDirectory_isUnderAndroidCacheDir() {
        val directory = MapLibreCacheStorage.resourceCacheDirectory(appContext)

        assertTrue(directory.canonicalPath.startsWith(appContext.cacheDir.canonicalPath))
    }

    @Test
    fun configureBeforeMapLibreInit_deletesOldDurableTileStore() {
        clearMigrationFlag()
        val oldTileStore = File(appContext.filesDir, "mbgl-offline.db")
        oldTileStore.parentFile?.mkdirs()
        oldTileStore.writeText("old tile data")
        val oldWal = File(appContext.filesDir, "mbgl-offline.db-wal")
        oldWal.writeText("old wal data")

        MapLibreCacheStorage.configureBeforeMapLibreInit(appContext)

        assertFalse(oldTileStore.exists())
        assertFalse(oldWal.exists())
        val newDirectory = MapLibreCacheStorage.resourceCacheDirectory(appContext)
        assertTrue(newDirectory.exists())
        assertTrue(newDirectory.canonicalPath.startsWith(appContext.cacheDir.canonicalPath))
    }

    private fun clearMigrationFlag() {
        appContext.getSharedPreferences("geovault_maplibre_cache_migration", 0)
            .edit()
            .clear()
            .commit()
    }
}
