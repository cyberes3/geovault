package com.geovault.common.logging

import android.content.Context
import java.io.File

internal object GeoVaultLoggingDatabasePaths {

    fun cacheDatabasePath(context: Context, dbFileName: String): String {
        return File(context.applicationContext.cacheDir, dbFileName).absolutePath
    }

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
