package com.geovault.common.update

import android.content.Context
import android.util.Log

class AppVersionChecker(
    private val giteaReleaseApi: GiteaReleaseApi = GiteaReleaseApi(),
    private val releaseAssetParser: ReleaseAssetParser = ReleaseAssetParser(),
    private val commitOrderResolver: CommitOrderResolver = CommitOrderResolver(giteaReleaseApi)
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
            val normalizedLocalSha = request.localFullCommitSha.trim().lowercase()
            val releaseRepoPath = request.releasesRepoPath?.takeIf { it.isNotBlank() } ?: request.codeRepoPath
            Log.i(
                UpdateCheckLog.TAG,
                "checkForUpdate start: codeRepo=${request.codeRepoPath} releasesRepo=$releaseRepoPath " +
                    "expectedAppName=${request.expectedAppName ?: "(any)"} maxReleases=${request.maxReleasesToScan} " +
                    "localSha=$normalizedLocalSha"
            )
            if (!FULL_SHA_REGEX.matches(normalizedLocalSha)) {
                Log.w(UpdateCheckLog.TAG, "checkForUpdate aborted: local commit is not a valid 40-char hex SHA")
                return VersionCheckResult.CheckFailed("localFullCommitSha is not a full 40-char commit hash")
            }

            val releases = giteaReleaseApi.fetchReleases(
                repoPath = releaseRepoPath,
                limit = request.maxReleasesToScan.coerceAtLeast(1)
            )
            if (releases.isEmpty()) {
                Log.w(UpdateCheckLog.TAG, "checkForUpdate: Gitea returned no releases (repo=$releaseRepoPath)")
                return VersionCheckResult.NoMatch("No releases returned from Gitea")
            }
            Log.d(
                UpdateCheckLog.TAG,
                "checkForUpdate: fetched ${releases.size} release(s) from $releaseRepoPath"
            )

            val matched = releaseAssetParser.findFirstMatchingReleaseAsset(
                releases = releases,
                apkNameRegex = request.apkNameRegex,
                expectedAppName = request.expectedAppName
            ) ?: run {
                Log.i(
                    UpdateCheckLog.TAG,
                    "checkForUpdate: no APK matched regex / app name (expectedApp=${request.expectedAppName ?: "any"})"
                )
                return VersionCheckResult.NoMatch("No release asset matched the APK naming regex")
            }

            Log.i(
                UpdateCheckLog.TAG,
                "checkForUpdate: matched asset=${matched.assetName} tag=${matched.releaseTag} " +
                    "releaseSha=${matched.releaseCommitSha}"
            )

            val resolvedReleaseCommitSha = giteaReleaseApi.resolveCommitSha(
                repoPath = request.codeRepoPath,
                commitRef = matched.releaseCommitSha
            ) ?: run {
                Log.w(
                    UpdateCheckLog.TAG,
                    "checkForUpdate: could not resolve full release SHA from tag ref=${matched.releaseCommitSha}"
                )
                return VersionCheckResult.CheckFailed("Could not resolve release commit SHA from release tag")
            }

            if (resolvedReleaseCommitSha == normalizedLocalSha) {
                Log.i(
                    UpdateCheckLog.TAG,
                    "checkForUpdate result=UpToDate (local commit equals latest matching release)"
                )
                return VersionCheckResult.UpToDate(
                    releaseCommitSha = resolvedReleaseCommitSha,
                    localCommitSha = normalizedLocalSha,
                    detail = "Local build commit is the latest release commit"
                )
            }

            val isNewer = commitOrderResolver.isReleaseCommitNewer(
                codeRepoPath = request.codeRepoPath,
                localCommitSha = normalizedLocalSha,
                releaseCommitSha = resolvedReleaseCommitSha
            )
            if (!isNewer) {
                Log.i(
                    UpdateCheckLog.TAG,
                    "checkForUpdate result=UpToDate (release commit not newer than local per Gitea)"
                )
                return VersionCheckResult.UpToDate(
                    releaseCommitSha = resolvedReleaseCommitSha,
                    localCommitSha = normalizedLocalSha,
                    detail = "Matched release commit is not newer than local commit"
                )
            }

            Log.i(
                UpdateCheckLog.TAG,
                "checkForUpdate result=UpdateAvailable app=${matched.appName} version=${matched.versionLabel}"
            )
            VersionCheckResult.UpdateAvailable(
                appName = matched.appName,
                versionLabel = matched.versionLabel,
                releaseUrl = matched.releaseUrl,
                releaseTag = matched.releaseTag,
                releaseCommitSha = resolvedReleaseCommitSha,
                localCommitSha = normalizedLocalSha
            )
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
