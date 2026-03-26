package com.geovault.common.update

data class VersionCheckRequest(
    val codeRepoPath: String,
    val releasesRepoPath: String? = null,
    val apkNameRegex: Regex,
    val localFullCommitSha: String,
    val expectedAppName: String? = null,
    val maxReleasesToScan: Int = 20
)

data class MatchedReleaseAsset(
    val appName: String,
    val versionLabel: String,
    val assetName: String,
    val releaseTag: String,
    val releaseUrl: String,
    val releaseCommitSha: String
)

sealed class VersionCheckResult {
    data class UpdateAvailable(
        val appName: String,
        val versionLabel: String,
        val releaseUrl: String,
        val releaseTag: String,
        val releaseCommitSha: String,
        val localCommitSha: String
    ) : VersionCheckResult()

    data class UpToDate(
        val releaseCommitSha: String? = null,
        val localCommitSha: String,
        val detail: String
    ) : VersionCheckResult()

    data class NoMatch(
        val detail: String
    ) : VersionCheckResult()

    data class Throttled(
        val detail: String,
        val lastCheckedAtMs: Long?
    ) : VersionCheckResult()

    data class CheckFailed(
        val detail: String,
        val cause: Throwable? = null
    ) : VersionCheckResult()
}
