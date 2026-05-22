package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import java.io.File
import org.maplibre.android.storage.FileSource

internal object MapLibreCacheStorage {
    private const val TAG = "MapLibreCacheStorage"
    private const val MAPLIBRE_PREFS_NAME = "MapboxSharedPreferences"
    private const val MAPLIBRE_RESOURCE_CACHE_PATH_KEY = "fileSourceResourcesCachePath"
    private const val MIGRATION_PREFS_NAME = "geovault_maplibre_cache_migration"
    private const val MIGRATION_DONE_KEY = "moved_tile_store_to_temp_cache_v1"
    private const val CACHE_DIR_NAME = "geovault-maplibre"
    private const val MAPLIBRE_DATABASE_NAME = "mbgl-offline.db"
    private const val LEGACY_INTERNAL_CACHE_DATABASE_NAME = "mbgl-cache.db"

    fun resourceCacheDirectory(context: Context): File =
        File(context.applicationContext.cacheDir, CACHE_DIR_NAME)

    fun configureBeforeMapLibreInit(context: Context) {
        val appContext = context.applicationContext
        val cacheDir = resourceCacheDirectory(appContext)
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Could not create MapLibre temp cache directory: ${cacheDir.absolutePath}")
            return
        }

        val mapLibrePrefs = appContext.getSharedPreferences(MAPLIBRE_PREFS_NAME, Context.MODE_PRIVATE)
        val previousPath = mapLibrePrefs.getString(MAPLIBRE_RESOURCE_CACHE_PATH_KEY, null)
        mapLibrePrefs.edit()
            .putString(MAPLIBRE_RESOURCE_CACHE_PATH_KEY, cacheDir.absolutePath)
            .apply()

        cleanupOldTileStores(appContext, cacheDir, previousPath)
    }

    fun ensureRuntimeResourceCachePath(context: Context) {
        val appContext = context.applicationContext
        val targetPath = resourceCacheDirectory(appContext).absolutePath
        if (FileSource.getResourcesCachePath(appContext) == targetPath) return
        FileSource.setResourcesCachePath(
            targetPath,
            object : FileSource.ResourcesCachePathChangeCallback {
                override fun onSuccess(path: String) {
                    Log.i(TAG, "MapLibre resource cache path set to temp cache: $path")
                }

                override fun onError(message: String) {
                    Log.w(TAG, "Failed to set MapLibre resource cache path: $message")
                }
            },
        )
    }

    private fun cleanupOldTileStores(
        context: Context,
        targetCacheDir: File,
        previousPath: String?,
    ) {
        val migrationPrefs = context.getSharedPreferences(MIGRATION_PREFS_NAME, Context.MODE_PRIVATE)
        if (migrationPrefs.getBoolean(MIGRATION_DONE_KEY, false)) return

        val candidates = buildSet {
            previousPath?.takeIf { it.isNotBlank() }?.let { add(File(it, MAPLIBRE_DATABASE_NAME)) }
            add(File(context.filesDir, MAPLIBRE_DATABASE_NAME))
            context.getExternalFilesDir(null)?.let { add(File(it, MAPLIBRE_DATABASE_NAME)) }
            add(File(context.cacheDir, LEGACY_INTERNAL_CACHE_DATABASE_NAME))
        }

        var cleanupSucceeded = true
        candidates.forEach { candidate ->
            if (candidate.isUnderDirectory(targetCacheDir) || candidate.isUnderDirectory(context.cacheDir)) {
                return@forEach
            }
            tileStoreFiles(candidate).forEach { file ->
                if (!file.exists()) return@forEach
                runCatching {
                    if (!file.delete()) {
                        cleanupSucceeded = false
                        Log.w(TAG, "Could not delete old MapLibre tile store file: ${file.absolutePath}")
                    } else {
                        Log.i(TAG, "Deleted old MapLibre tile store file: ${file.absolutePath}")
                    }
                }.onFailure { error ->
                    cleanupSucceeded = false
                    Log.w(TAG, "Failed deleting old MapLibre tile store file: ${file.absolutePath}", error)
                }
            }
        }

        if (cleanupSucceeded) {
            migrationPrefs.edit().putBoolean(MIGRATION_DONE_KEY, true).apply()
        }
    }

    private fun tileStoreFiles(databaseFile: File): List<File> = listOf(
        databaseFile,
        File(databaseFile.path + "-journal"),
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
    )

    private fun File.isUnderDirectory(directory: File): Boolean {
        val parent = runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }
        val child = runCatching { canonicalFile }.getOrElse { absoluteFile }
        return child == parent || child.path.startsWith(parent.path + File.separator)
    }
}
