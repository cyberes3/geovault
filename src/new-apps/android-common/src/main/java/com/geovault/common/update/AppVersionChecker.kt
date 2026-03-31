package com.geovault.common.update

import android.content.Context

class AppVersionChecker(
    private val apiClient: VersionCheckApiClient = WorkerVersionCheckApiClient()
) {
    fun checkForUpdateIfDue(
        context: Context,
        request: VersionCheckRequest,
        rateLimitKey: String,
        minIntervalMs: Long = ONE_HOUR_MS
    ): VersionCheckResult {
        val normalizedLocalSha = request.localFullCommitSha.trim().lowercase()
        val decision = VersionCheckRateLimiter.shouldRunAndMark(
            context = context.applicationContext,
            key = rateLimitKey,
            minIntervalMs = minIntervalMs
        )
        if (!decision.shouldRun) {
            val cached = cachedUpdateOrNull(context, rateLimitKey, normalizedLocalSha)
            if (cached != null) return cached
            return VersionCheckResult.Throttled(
                detail = "Version check skipped by rate limiter",
                lastCheckedAtMs = decision.lastCheckedAtMs
            )
        }
        return persistAndReturn(context, rateLimitKey, checkForUpdate(request))
    }

    fun checkForUpdate(request: VersionCheckRequest): VersionCheckResult {
        val appName = request.appName.trim()
        val localSha = request.localFullCommitSha.trim().lowercase()
        if (appName.isBlank()) return VersionCheckResult.CheckFailed("appName is blank")
        if (!FULL_SHA_REGEX.matches(localSha)) {
            return VersionCheckResult.CheckFailed("localFullCommitSha is not a full 40-char commit hash")
        }

        return when (val result = apiClient.checkForUpdate(VersionCheckRequest(appName, localSha))) {
            is WorkerCheckApiResult.NoMatch -> VersionCheckResult.NoMatch(result.detail)
            is WorkerCheckApiResult.Failed -> VersionCheckResult.CheckFailed(result.detail, result.cause)
            is WorkerCheckApiResult.Success -> {
                val payload = result.payload
                if (payload.isLatest) {
                    VersionCheckResult.UpToDate(
                        releaseCommitSha = payload.releaseCommitSha,
                        localCommitSha = payload.localCommitSha,
                        detail = "Local build commit is the latest release commit"
                    )
                } else {
                    VersionCheckResult.UpdateAvailable(
                        appName = payload.appName,
                        versionLabel = payload.versionLabel,
                        releaseUrl = payload.releasePageUrl,
                        releaseTag = payload.releaseTag,
                        releaseCommitSha = payload.releaseCommitSha,
                        localCommitSha = payload.localCommitSha
                    )
                }
            }
        }
    }

    private fun cachedUpdateOrNull(
        context: Context,
        rateLimitKey: String,
        normalizedLocalSha: String
    ): VersionCheckResult.UpdateAvailable? {
        if (!FULL_SHA_REGEX.matches(normalizedLocalSha)) return null
        return UpdateAvailableCacheStore.read(context, rateLimitKey, normalizedLocalSha)
    }

    private fun persistAndReturn(
        context: Context,
        rateLimitKey: String,
        result: VersionCheckResult
    ): VersionCheckResult {
        when (result) {
            is VersionCheckResult.UpdateAvailable -> UpdateAvailableCacheStore.write(context, rateLimitKey, result)
            is VersionCheckResult.UpToDate -> UpdateAvailableCacheStore.clear(context, rateLimitKey)
            else -> Unit
        }
        return result
    }

    companion object {
        private val FULL_SHA_REGEX = Regex("^[0-9a-f]{40}$")
        const val ONE_HOUR_MS: Long = 60L * 60L * 1000L
    }
}
