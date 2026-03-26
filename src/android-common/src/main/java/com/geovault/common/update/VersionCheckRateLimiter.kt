package com.geovault.common.update

import android.content.Context

object VersionCheckRateLimiter {
    private const val PREFS_NAME = "gv_common_version_check_rate_limiter"
    private const val KEY_PREFIX = "last_check_ms:"

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
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storageKey = KEY_PREFIX + normalizedKey
        val last = prefs.getLong(storageKey, -1L).takeIf { it >= 0L }
        val shouldRun = last == null || nowMs - last >= minIntervalMs
        if (shouldRun) {
            prefs.edit().putLong(storageKey, nowMs).apply()
        }
        return RateLimitDecision(shouldRun = shouldRun, lastCheckedAtMs = last)
    }

    data class RateLimitDecision(
        val shouldRun: Boolean,
        val lastCheckedAtMs: Long?
    )
}
