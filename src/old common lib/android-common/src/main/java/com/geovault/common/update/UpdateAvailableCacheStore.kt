package com.geovault.common.update

import android.content.Context
import org.json.JSONObject
import java.io.File

internal object UpdateAvailableCacheStore {
    private const val CACHE_DIR_NAME = "gv_common_version_check_state"
    private val FULL_SHA_REGEX = Regex("^[0-9a-f]{40}$")

    fun read(
        context: Context,
        key: String,
        normalizedLocalSha: String
    ): VersionCheckResult.UpdateAvailable? {
        val file = stateFile(context.applicationContext, key) ?: return null
        if (!file.exists()) return null
        val cached = parse(file.readText()) ?: return null
        if (cached.releaseCommitSha == normalizedLocalSha) {
            clear(context, key)
            return null
        }
        return cached.copy(localCommitSha = normalizedLocalSha)
    }

    fun write(
        context: Context,
        key: String,
        update: VersionCheckResult.UpdateAvailable
    ) {
        val file = stateFile(context.applicationContext, key) ?: return
        val payload = JSONObject()
            .put("appName", update.appName)
            .put("versionLabel", update.versionLabel)
            .put("releaseUrl", update.releaseUrl)
            .put("releaseTag", update.releaseTag)
            .put("releaseCommitSha", update.releaseCommitSha.lowercase())
        file.writeText(payload.toString())
    }

    fun clear(context: Context, key: String) {
        val file = stateFile(context.applicationContext, key) ?: return
        if (file.exists()) {
            file.delete()
        }
    }

    private fun parse(raw: String): VersionCheckResult.UpdateAvailable? {
        return try {
            val json = JSONObject(raw)
            val appName = json.optString("appName", "").trim()
            val versionLabel = json.optString("versionLabel", "").trim()
            val releaseUrl = json.optString("releaseUrl", "").trim()
            val releaseTag = json.optString("releaseTag", "").trim()
            val releaseCommitSha = json.optString("releaseCommitSha", "").trim().lowercase()
            if (
                appName.isBlank() ||
                versionLabel.isBlank() ||
                releaseUrl.isBlank() ||
                releaseTag.isBlank() ||
                !FULL_SHA_REGEX.matches(releaseCommitSha)
            ) {
                return null
            }
            VersionCheckResult.UpdateAvailable(
                appName = appName,
                versionLabel = versionLabel,
                releaseUrl = releaseUrl,
                releaseTag = releaseTag,
                releaseCommitSha = releaseCommitSha,
                localCommitSha = ""
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun stateFile(context: Context, key: String): File? {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return null
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val safeKey = normalizedKey.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        return File(dir, "$safeKey.json")
    }
}
