package com.geovault.common.update

import android.content.Context
import android.util.Log

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
            val cachedUpdate = cachedUpdateOrNull(context, rateLimitKey, normalizedLocalSha)
            if (cachedUpdate != null) {
                Log.i(
                    UpdateCheckLog.TAG,
                    "if_due key=$rateLimitKey action=rate_limited outcome=cached_update " +
                        "versionLabel=${cachedUpdate.versionLabel} tag=${cachedUpdate.releaseTag}"
                )
                return cachedUpdate
            }
            Log.d(
                UpdateCheckLog.TAG,
                "if_due key=$rateLimitKey action=rate_limited outcome=throttled " +
                    "lastCheckedAtMs=${decision.lastCheckedAtMs}"
            )
            return VersionCheckResult.Throttled(
                detail = "Version check skipped by rate limiter",
                lastCheckedAtMs = decision.lastCheckedAtMs
            )
        }
        Log.i(
            UpdateCheckLog.TAG,
            "if_due key=$rateLimitKey action=network_check appName=${request.appName.trim()}"
        )
        return persistAndReturn(context, rateLimitKey, checkForUpdate(request))
    }

    fun checkForUpdate(request: VersionCheckRequest): VersionCheckResult {
        return try {
            val normalizedAppName = request.appName.trim()
            val normalizedLocalSha = request.localFullCommitSha.trim().lowercase()
            Log.i(
                UpdateCheckLog.TAG,
                "checkForUpdate start: appName=$normalizedAppName localSha=$normalizedLocalSha"
            )
            if (normalizedAppName.isBlank()) {
                Log.w(UpdateCheckLog.TAG, "checkForUpdate aborted: appName is blank")
                return VersionCheckResult.CheckFailed("appName is blank")
            }
            if (!FULL_SHA_REGEX.matches(normalizedLocalSha)) {
                Log.w(UpdateCheckLog.TAG, "checkForUpdate aborted: local commit is not a valid 40-char hex SHA")
                return VersionCheckResult.CheckFailed("localFullCommitSha is not a full 40-char commit hash")
            }

            val mappedResult = when (
                val apiResult = apiClient.checkForUpdate(
                    request = VersionCheckRequest(
                        appName = normalizedAppName,
                        localFullCommitSha = normalizedLocalSha
                    )
                )
            ) {
                is WorkerCheckApiResult.NoMatch -> {
                    Log.i(UpdateCheckLog.TAG, "checkForUpdate result=NoMatch detail=${apiResult.detail}")
                    VersionCheckResult.NoMatch(apiResult.detail)
                }

                is WorkerCheckApiResult.Failed -> {
                    Log.w(UpdateCheckLog.TAG, "checkForUpdate result=CheckFailed detail=${apiResult.detail}")
                    VersionCheckResult.CheckFailed(
                        detail = apiResult.detail,
                        cause = apiResult.cause
                    )
                }

                is WorkerCheckApiResult.Success -> {
                    val payload = apiResult.payload
                    if (payload.isLatest) {
                        Log.i(UpdateCheckLog.TAG, "checkForUpdate result=UpToDate app=${payload.appName}")
                        VersionCheckResult.UpToDate(
                            releaseCommitSha = payload.releaseCommitSha,
                            localCommitSha = payload.localCommitSha,
                            detail = "Local build commit is the latest release commit"
                        )
                    } else {
                        Log.i(
                            UpdateCheckLog.TAG,
                            "checkForUpdate result=UpdateAvailable app=${payload.appName} version=${payload.versionLabel}"
                        )
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
            mappedResult
        } catch (e: Exception) {
            Log.e(UpdateCheckLog.TAG, "checkForUpdate: unexpected exception", e)
            VersionCheckResult.CheckFailed(detail = "Version check failed", cause = e)
        }
    }

    private fun cachedUpdateOrNull(
        context: Context,
        rateLimitKey: String,
        normalizedLocalSha: String
    ): VersionCheckResult.UpdateAvailable? {
        if (!FULL_SHA_REGEX.matches(normalizedLocalSha)) return null
        return try {
            UpdateAvailableCacheStore.read(context, rateLimitKey, normalizedLocalSha)
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "failed reading cached UpdateAvailable", e)
            null
        }
    }

    private fun persistAndReturn(
        context: Context,
        rateLimitKey: String,
        result: VersionCheckResult
    ): VersionCheckResult {
        try {
            when (result) {
                is VersionCheckResult.UpdateAvailable -> UpdateAvailableCacheStore.write(context, rateLimitKey, result)
                is VersionCheckResult.UpToDate -> UpdateAvailableCacheStore.clear(context, rateLimitKey)
                else -> Unit
            }
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "failed updating cached UpdateAvailable state", e)
        }
        return result
    }

    companion object {
        private val FULL_SHA_REGEX = Regex("^[0-9a-f]{40}$")
        const val ONE_HOUR_MS: Long = 60L * 60L * 1000L
    }
}
