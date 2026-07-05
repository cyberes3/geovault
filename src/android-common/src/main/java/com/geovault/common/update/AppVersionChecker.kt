package com.geovault.common.update

import android.content.Context
import android.util.Log

class AppVersionChecker(
    private val apiClient: VersionCheckApiClient = WorkerVersionCheckApiClient()
) {
    /**
     * Always runs a live check against the release worker. If the live check fails or finds
     * no match, falls back to the last cached [VersionCheckResult.UpdateAvailable] for the
     * current local commit (if any) so an "out of date" prompt keeps showing through
     * transient network failures, until a live check confirms the app is up to date.
     */
    fun checkForUpdateIfDue(
        context: Context,
        request: VersionCheckRequest,
        cacheKey: String,
    ): VersionCheckResult {
        Log.i(
            UpdateCheckLog.TAG,
            "if_due key=$cacheKey action=network_check appName=${request.appName.trim()}"
        )
        val normalizedLocalSha = request.localFullCommitSha.trim().lowercase()
        return persistAndReturn(context, cacheKey, normalizedLocalSha, checkForUpdate(request))
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
                        val apkUrl = payload.latestApkUrl.trim()
                        if (apkUrl.isBlank()) {
                            Log.w(UpdateCheckLog.TAG, "checkForUpdate result=CheckFailed reason=no_apk_url")
                            return VersionCheckResult.CheckFailed("Release worker returned no APK download URL")
                        }
                        Log.i(
                            UpdateCheckLog.TAG,
                            "checkForUpdate result=UpdateAvailable app=${payload.appName} version=${payload.versionLabel}"
                        )
                        val tag = payload.releaseTag.trim()
                        val assetName = payload.apkAssetName.ifBlank { "$tag.apk" }
                        VersionCheckResult.UpdateAvailable(
                            appName = payload.appName,
                            versionLabel = payload.versionLabel,
                            releaseUrl = payload.releasePageUrl,
                            releaseTag = tag,
                            releaseCommitSha = payload.releaseCommitSha,
                            localCommitSha = payload.localCommitSha,
                            apkDownloadUrl = apkUrl,
                            apkAssetName = assetName,
                            apkSizeBytes = payload.apkSizeBytes?.takeIf { it > 0L },
                            releasePublishedAtIso = payload.releasePublishedAt,
                            releaseTitle = payload.releaseTitle,
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

    /**
     * Reads the last cached [VersionCheckResult.UpdateAvailable] for [cacheKey], if any, without
     * making a network call. Used to show a previously detected "out of date" state instantly
     * (e.g. on app launch) while a live check runs in the background.
     */
    fun peekCachedUpdate(
        context: Context,
        cacheKey: String,
        localFullCommitSha: String
    ): VersionCheckResult.UpdateAvailable? {
        return cachedUpdateOrNull(context, cacheKey, localFullCommitSha.trim().lowercase())
    }

    private fun cachedUpdateOrNull(
        context: Context,
        cacheKey: String,
        normalizedLocalSha: String
    ): VersionCheckResult.UpdateAvailable? {
        if (!FULL_SHA_REGEX.matches(normalizedLocalSha)) return null
        return try {
            UpdateAvailableCacheStore.read(context, cacheKey, normalizedLocalSha)
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "failed reading cached UpdateAvailable", e)
            null
        }
    }

    private fun persistAndReturn(
        context: Context,
        cacheKey: String,
        normalizedLocalSha: String,
        result: VersionCheckResult
    ): VersionCheckResult {
        return try {
            when (result) {
                is VersionCheckResult.UpdateAvailable -> {
                    UpdateAvailableCacheStore.write(context, cacheKey, result)
                    result
                }

                is VersionCheckResult.UpToDate -> {
                    UpdateAvailableCacheStore.clear(context, cacheKey)
                    result
                }

                is VersionCheckResult.CheckFailed, is VersionCheckResult.NoMatch -> {
                    val cachedUpdate = cachedUpdateOrNull(context, cacheKey, normalizedLocalSha)
                    if (cachedUpdate != null) {
                        Log.i(
                            UpdateCheckLog.TAG,
                            "persistAndReturn key=$cacheKey action=live_check_failed outcome=cached_update " +
                                "versionLabel=${cachedUpdate.versionLabel} tag=${cachedUpdate.releaseTag}"
                        )
                        cachedUpdate
                    } else {
                        result
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "failed updating cached UpdateAvailable state", e)
            result
        }
    }

    companion object {
        private val FULL_SHA_REGEX = Regex("^[0-9a-f]{40}$")
    }
}
