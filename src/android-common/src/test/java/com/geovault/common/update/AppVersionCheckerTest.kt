package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppVersionCheckerTest {
    @Test
    fun returnsUpdateAvailableWhenWorkerReportsNewerVersion() {
        val checker = AppVersionChecker(
            apiClient = FakeVersionCheckApiClient(
                result = WorkerCheckApiResult.Success(
                    payload = WorkerCheckPayload(
                        isLatest = false,
                        appName = "GeoVault Live Tracker",
                        versionLabel = "2026-03-26 9e89dc347d",
                        latestApkUrl = "https://example/tracker.apk",
                        releasePageUrl = "https://example/release",
                        releaseTag = "tracker-2026-03-26-9e89dc347d",
                        releaseCommitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        localCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        releasesRepo = "cyberes/geovault-app-release",
                        codeRepo = "cyberes/geovault"
                    )
                )
            )
        )

        val result = checker.checkForUpdate(
            VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )
        assertTrue(result is VersionCheckResult.UpdateAvailable)
        val update = result as VersionCheckResult.UpdateAvailable
        assertEquals("GeoVault Live Tracker", update.appName)
        assertEquals("tracker-2026-03-26-9e89dc347d", update.releaseTag)
    }

    @Test
    fun returnsUpToDateWhenWorkerReportsLatest() {
        val checker = AppVersionChecker(
            apiClient = FakeVersionCheckApiClient(
                result = WorkerCheckApiResult.Success(
                    payload = WorkerCheckPayload(
                        isLatest = true,
                        appName = "GeoVault Live Tracker",
                        versionLabel = "2026-03-26 9e89dc347d",
                        latestApkUrl = "https://example/tracker.apk",
                        releasePageUrl = "https://example/release",
                        releaseTag = "tracker-2026-03-26-9e89dc347d",
                        releaseCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        localCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        releasesRepo = "cyberes/geovault-app-release",
                        codeRepo = "cyberes/geovault"
                    )
                )
            )
        )

        val result = checker.checkForUpdate(
            VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )
        assertTrue(result is VersionCheckResult.UpToDate)
    }

    @Test
    fun returnsNoMatchWhenWorkerReturnsNoMatch() {
        val checker = AppVersionChecker(
            apiClient = FakeVersionCheckApiClient(
                result = WorkerCheckApiResult.NoMatch("No matching release found")
            )
        )

        val result = checker.checkForUpdate(
            VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )
        assertTrue(result is VersionCheckResult.NoMatch)
    }

    @Test
    fun returnsCheckFailedForInvalidLocalShaWithoutCallingApi() {
        val fakeClient = FakeVersionCheckApiClient(
            result = WorkerCheckApiResult.Failed("should not be used")
        )
        val checker = AppVersionChecker(apiClient = fakeClient)

        val result = checker.checkForUpdate(
            VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "short-sha"
            )
        )

        assertTrue(result is VersionCheckResult.CheckFailed)
        assertEquals(0, fakeClient.invocations)
    }

    private class FakeVersionCheckApiClient(
        private val result: WorkerCheckApiResult
    ) : VersionCheckApiClient {
        var invocations: Int = 0

        override fun checkForUpdate(request: VersionCheckRequest): WorkerCheckApiResult {
            invocations += 1
            return result
        }
    }
}
