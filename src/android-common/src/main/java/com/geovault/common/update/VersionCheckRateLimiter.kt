package com.geovault.common.update

import android.content.Context
import java.io.File

object VersionCheckRateLimiter {
    private const val CACHE_DIR_NAME = "gv_common_version_check_rate_limiter"

    @Synchronized
    fun shouldRunAndMark(
        context: Context,
        key: String,
        minIntervalMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): RateLimitDecision {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) {
            return RateLimitDecision(shouldRun = true, lastCheckedAtMs = null)
        }
        val markerFile = markerFile(context.applicationContext, normalizedKey)
        val last = readLastCheckedAtMs(markerFile)
        val shouldRun = last == null || nowMs - last >= minIntervalMs
        if (shouldRun) {
            writeLastCheckedAtMs(markerFile, nowMs)
        }
        return RateLimitDecision(shouldRun = shouldRun, lastCheckedAtMs = last)
    }

    private fun markerFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val safeKey = key.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        return File(dir, "$safeKey.ts")
    }

    private fun readLastCheckedAtMs(file: File): Long? {
        if (!file.exists()) return null
        return try {
            file.readText().trim().toLongOrNull()?.takeIf { it >= 0L }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLastCheckedAtMs(file: File, timestampMs: Long) {
        try {
            file.writeText(timestampMs.toString())
        } catch (_: Exception) {
            // Best effort persistence.
        }
    }

    data class RateLimitDecision(
        val shouldRun: Boolean,
        val lastCheckedAtMs: Long?
    )
}
