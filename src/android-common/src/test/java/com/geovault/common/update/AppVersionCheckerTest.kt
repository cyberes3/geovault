package com.geovault.common.update

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun returnsCachedUpdateWhenThrottled() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val firstClient = FakeVersionCheckApiClient(
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
        val checker = AppVersionChecker(apiClient = firstClient)

        val first = checker.checkForUpdateIfDue(
            context = context,
            request = VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            ),
            rateLimitKey = "tracker-test-cache",
            minIntervalMs = AppVersionChecker.ONE_HOUR_MS
        )
        assertTrue(first is VersionCheckResult.UpdateAvailable)
        assertEquals(1, firstClient.invocations)

        val throttledChecker = AppVersionChecker(
            apiClient = FakeVersionCheckApiClient(
                result = WorkerCheckApiResult.Failed("should not run when throttled")
            )
        )
        val second = throttledChecker.checkForUpdateIfDue(
            context = context,
            request = VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            ),
            rateLimitKey = "tracker-test-cache",
            minIntervalMs = AppVersionChecker.ONE_HOUR_MS
        )
        assertTrue(second is VersionCheckResult.UpdateAvailable)
    }

    @Test
    fun clearsCachedUpdateWhenInstalledBuildMatchesReleaseCommit() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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

        val first = checker.checkForUpdateIfDue(
            context = context,
            request = VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            ),
            rateLimitKey = "tracker-test-clear",
            minIntervalMs = AppVersionChecker.ONE_HOUR_MS
        )
        assertTrue(first is VersionCheckResult.UpdateAvailable)

        val second = checker.checkForUpdateIfDue(
            context = context,
            request = VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            ),
            rateLimitKey = "tracker-test-clear",
            minIntervalMs = AppVersionChecker.ONE_HOUR_MS
        )
        assertFalse(second is VersionCheckResult.UpdateAvailable)
        assertTrue(second is VersionCheckResult.Throttled || second is VersionCheckResult.UpToDate)
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
