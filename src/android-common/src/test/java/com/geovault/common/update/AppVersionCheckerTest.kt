package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppVersionCheckerTest {
    private val apkRegex = Regex("^(.+?)\\s(\\d{4}-\\d{2}-\\d{2}\\s[0-9a-fA-F]{10})\\.apk$")

    @Test
    fun returnsUpdateAvailableWhenReleaseCommitIsNewer() {
        val fakeApi = FakeGiteaApi().apply {
            releases = listOf(
                GiteaReleaseDto(
                    tagName = "tracker-2026-03-23-bbbbbbbbbb",
                    htmlUrl = "https://git.evulid.cc/cyberes/geovault-app-release/releases/tag/tracker-2026-03-23",
                    assets = listOf(
                        GiteaReleaseAssetDto(
                            name = "GeoVault Live Tracker 2026-03-23 bbbbbbbbbb.apk",
                            browserDownloadUrl = "https://example/tracker.apk"
                        )
                    )
                )
            )
            compareStatus = "ahead"
            commitDates["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"] = Instant.parse("2026-03-20T10:00:00Z")
            commitDates["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"] = Instant.parse("2026-03-23T10:00:00Z")
            resolvedShas["bbbbbbbbbb"] = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        }
        val checker = AppVersionChecker(
            giteaReleaseApi = fakeApi,
            releaseAssetParser = ReleaseAssetParser(),
            commitOrderResolver = CommitOrderResolver(fakeApi)
        )
        val result = checker.checkForUpdate(
            VersionCheckRequest(
                codeRepoPath = "cyberes/geovault",
                releasesRepoPath = "cyberes/geovault-app-release",
                apkNameRegex = apkRegex,
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                expectedAppName = "GeoVault Live Tracker"
            )
        )
        assertTrue(result is VersionCheckResult.UpdateAvailable)
        val update = result as VersionCheckResult.UpdateAvailable
        assertEquals("GeoVault Live Tracker", update.appName)
    }

    @Test
    fun returnsUpToDateWhenCommitsMatch() {
        val fakeApi = FakeGiteaApi().apply {
            releases = listOf(
                GiteaReleaseDto(
                    tagName = "tracker-2026-03-23-aaaaaaaaaa",
                    htmlUrl = "https://git.evulid.cc/cyberes/geovault-app-release/releases/tag/tracker-2026-03-23",
                    assets = listOf(
                        GiteaReleaseAssetDto(
                            name = "GeoVault Live Tracker 2026-03-23 aaaaaaaaaa.apk",
                            browserDownloadUrl = "https://example/tracker.apk"
                        )
                    )
                )
            )
            resolvedShas["aaaaaaaaaa"] = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
        val checker = AppVersionChecker(
            giteaReleaseApi = fakeApi,
            releaseAssetParser = ReleaseAssetParser(),
            commitOrderResolver = CommitOrderResolver(fakeApi)
        )
        val result = checker.checkForUpdate(
            VersionCheckRequest(
                codeRepoPath = "cyberes/geovault",
                releasesRepoPath = "cyberes/geovault-app-release",
                apkNameRegex = apkRegex,
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                expectedAppName = "GeoVault Live Tracker"
            )
        )
        assertTrue(result is VersionCheckResult.UpToDate)
    }

    @Test
    fun scanStopsAfterFirstMatchingAsset() {
        val fakeApi = FakeGiteaApi().apply {
            releases = listOf(
                GiteaReleaseDto(
                    tagName = "tracker-2026-03-25-bbbbbbbbbb",
                    htmlUrl = "https://example/first",
                    assets = listOf(
                        GiteaReleaseAssetDto(
                            name = "GeoVault Live Tracker 2026-03-25 bbbbbbbbbb.apk",
                            browserDownloadUrl = "https://example/first.apk"
                        )
                    )
                ),
                GiteaReleaseDto(
                    tagName = "tracker-2026-03-24-cccccccccc",
                    htmlUrl = "https://example/second",
                    assets = listOf(
                        GiteaReleaseAssetDto(
                            name = "GeoVault Live Tracker 2026-03-24 cccccccccc.apk",
                            browserDownloadUrl = "https://example/second.apk"
                        )
                    )
                )
            )
            compareStatus = "ahead"
            commitDates["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"] = Instant.parse("2026-03-20T10:00:00Z")
            commitDates["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"] = Instant.parse("2026-03-25T10:00:00Z")
            resolvedShas["bbbbbbbbbb"] = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            resolvedShas["cccccccccc"] = "cccccccccccccccccccccccccccccccccccccccc"
        }
        val checker = AppVersionChecker(
            giteaReleaseApi = fakeApi,
            releaseAssetParser = ReleaseAssetParser(),
            commitOrderResolver = CommitOrderResolver(fakeApi)
        )

        val result = checker.checkForUpdate(
            VersionCheckRequest(
                codeRepoPath = "cyberes/geovault",
                releasesRepoPath = "cyberes/geovault-app-release",
                apkNameRegex = apkRegex,
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                expectedAppName = "GeoVault Live Tracker"
            )
        )
        assertTrue(result is VersionCheckResult.UpdateAvailable)
        val update = result as VersionCheckResult.UpdateAvailable
        assertEquals("tracker-2026-03-25-bbbbbbbbbb", update.releaseTag)
    }

    private class FakeGiteaApi : GiteaReleaseApi() {
        var releases: List<GiteaReleaseDto> = emptyList()
        var compareStatus: String? = null
        val commitDates = mutableMapOf<String, Instant>()
        val resolvedShas = mutableMapOf<String, String>()

        override fun fetchReleases(repoPath: String, limit: Int): List<GiteaReleaseDto> = releases

        override fun compareCommits(repoPath: String, baseCommit: String, headCommit: String): GiteaCommitCompareDto? {
            return compareStatus?.let { GiteaCommitCompareDto(status = it, totalCommits = null) }
        }

        override fun fetchCommitDate(repoPath: String, commitSha: String): Instant? {
            return commitDates[commitSha]
        }

        override fun resolveCommitSha(repoPath: String, commitRef: String): String? {
            val normalized = commitRef.lowercase()
            return when {
                Regex("^[0-9a-f]{40}$").matches(normalized) -> normalized
                else -> resolvedShas[normalized]
            }
        }
    }
}
