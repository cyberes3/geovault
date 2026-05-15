package com.geovault.common.update

import android.content.Context
import org.json.JSONObject
import java.io.File

object UpdateAvailableCacheStore {
    private const val CACHE_DIR_NAME = "gv_common_update_cache"
    private const val SCHEMA_VERSION = 2

    fun read(
        context: Context,
        key: String,
        normalizedLocalSha: String
    ): VersionCheckResult.UpdateAvailable? {
        val file = cacheFile(context, key) ?: return null
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            if (json.optInt("schemaVersion", 1) < SCHEMA_VERSION) return null
            val cachedLocal = json.optString("localCommitSha").trim().lowercase()
            if (cachedLocal != normalizedLocalSha) return null
            val apkDownloadUrl = json.optString("apkDownloadUrl").trim()
            if (apkDownloadUrl.isBlank()) return null
            val apkSizeOpt = json.optLong("apkSizeBytes", -1L)
            val apkSizeBytes = if (json.has("apkSizeBytes") && apkSizeOpt > 0L) apkSizeOpt else null
            VersionCheckResult.UpdateAvailable(
                appName = json.optString("appName"),
                versionLabel = json.optString("versionLabel"),
                releaseUrl = json.optString("releaseUrl"),
                releaseTag = json.optString("releaseTag"),
                releaseCommitSha = json.optString("releaseCommitSha"),
                localCommitSha = cachedLocal,
                apkDownloadUrl = apkDownloadUrl,
                apkAssetName = json.optString("apkAssetName").ifBlank { "${json.optString("releaseTag")}.apk" },
                apkSizeBytes = apkSizeBytes,
                releasePublishedAtIso = json.optString("releasePublishedAtIso"),
                releaseTitle = json.optString("releaseTitle"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun write(context: Context, key: String, value: VersionCheckResult.UpdateAvailable) {
        val file = cacheFile(context, key) ?: return
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appName", value.appName)
            .put("versionLabel", value.versionLabel)
            .put("releaseUrl", value.releaseUrl)
            .put("releaseTag", value.releaseTag)
            .put("releaseCommitSha", value.releaseCommitSha)
            .put("localCommitSha", value.localCommitSha)
            .put("apkDownloadUrl", value.apkDownloadUrl)
            .put("apkAssetName", value.apkAssetName)
            .put("releasePublishedAtIso", value.releasePublishedAtIso)
            .put("releaseTitle", value.releaseTitle)
        if (value.apkSizeBytes != null) {
            json.put("apkSizeBytes", value.apkSizeBytes)
        }
        file.writeText(json.toString())
    }

    fun clear(context: Context, key: String) {
        val file = cacheFile(context, key) ?: return
        if (file.exists()) {
            file.delete()
        }
    }

    private fun cacheFile(context: Context, key: String): File? {
        val safeKey = key.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        if (safeKey.isBlank()) return null
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$safeKey.json")
    }
}
