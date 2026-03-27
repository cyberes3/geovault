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
        val decision = VersionCheckRateLimiter.shouldRunAndMark(
            context = context.applicationContext,
            key = rateLimitKey,
            minIntervalMs = minIntervalMs
        )
        if (!decision.shouldRun) {
            Log.d(
                UpdateCheckLog.TAG,
                "checkForUpdate skipped by rate limiter: key=$rateLimitKey minIntervalMs=$minIntervalMs " +
                    "lastCheckedAtMs=${decision.lastCheckedAtMs}"
            )
            return VersionCheckResult.Throttled(
                detail = "Version check skipped by rate limiter",
                lastCheckedAtMs = decision.lastCheckedAtMs
            )
        }
        return checkForUpdate(request)
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

            when (
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
        } catch (e: Exception) {
            Log.e(UpdateCheckLog.TAG, "checkForUpdate: unexpected exception", e)
            VersionCheckResult.CheckFailed(detail = "Version check failed", cause = e)
        }
    }

    companion object {
        private val FULL_SHA_REGEX = Regex("^[0-9a-f]{40}$")
        const val ONE_HOUR_MS: Long = 60L * 60L * 1000L
    }
}
