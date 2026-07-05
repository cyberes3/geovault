package com.geovault.common.update

data class VersionCheckRequest(
    val appName: String,
    val localFullCommitSha: String
)

sealed class VersionCheckResult {
    data class UpdateAvailable(
        val appName: String,
        val versionLabel: String,
        val releaseUrl: String,
        val releaseTag: String,
        val releaseCommitSha: String,
        val localCommitSha: String,
        val apkDownloadUrl: String,
        val apkAssetName: String,
        val apkSizeBytes: Long?,
        val releasePublishedAtIso: String,
        val releaseTitle: String,
    ) : VersionCheckResult()

    data class UpToDate(
        val releaseCommitSha: String? = null,
        val localCommitSha: String,
        val detail: String
    ) : VersionCheckResult()

    data class NoMatch(
        val detail: String
    ) : VersionCheckResult()

    data class CheckFailed(
        val detail: String,
        val cause: Throwable? = null
    ) : VersionCheckResult()
}
