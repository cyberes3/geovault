package com.geovault.common.logging

import android.content.Context
import java.io.File

/**
 * Returns the absolute path for a cache-directory SQLite database file.
 * Usable by any module that follows the same cache-directory convention as the
 * capture-log and telemetry stores.
 */
fun cacheDatabasePathPublic(context: Context, dbFileName: String): String =
    File(context.applicationContext.cacheDir, dbFileName).absolutePath

/**
 * Deletes a SQLite database file together with its WAL/SHM sidecar files.
 * Usable by any module managing its own cache-directory SQLite stores.
 */
fun deleteCacheDatabaseFiles(context: Context, dbFileName: String) {
    val mainFile = File(cacheDatabasePathPublic(context, dbFileName))
    if (mainFile.exists()) mainFile.delete()
    File("${mainFile.path}-wal").delete()
    File("${mainFile.path}-shm").delete()
}

internal object GeoVaultLoggingDatabasePaths {

    fun cacheDatabasePath(context: Context, dbFileName: String): String =
        cacheDatabasePathPublic(context, dbFileName)

    fun deleteLegacyLoggingDatabaseIfPresent(context: Context, dbFileName: String) {
        val appContext = context.applicationContext
        deleteSqliteFiles(appContext.getDatabasePath(dbFileName))
        val databasesDir = File(appContext.applicationInfo.dataDir, "databases")
        if (databasesDir.isDirectory) {
            databasesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("geovault_") && file.name.endsWith(".sqlite")) {
                    deleteSqliteFiles(file)
                }
            }
        }
    }

    private fun deleteSqliteFiles(mainFile: File) {
        if (!mainFile.exists()) {
            return
        }
        mainFile.delete()
        File(mainFile.path + "-wal").delete()
        File(mainFile.path + "-shm").delete()
    }
}
