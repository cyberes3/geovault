package com.geovault.common.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppVersionCheckerHttpsTest {

    @Test
    fun checkForUpdateRejectsHttpApkUrl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val checker = AppVersionChecker(
            apiClient = object : VersionCheckApiClient {
                override fun checkForUpdate(request: VersionCheckRequest): WorkerCheckApiResult {
                    return WorkerCheckApiResult.Success(
                        WorkerCheckPayload(
                            isLatest = false,
                            appName = GeoVaultAndroidReleaseIdentity.Uploader.WORKER_APP_NAME,
                            versionLabel = "v3",
                            releasePageUrl = "https://example.test/release",
                            releaseTag = "v3",
                            releaseCommitSha = "c".repeat(40),
                            localCommitSha = "d".repeat(40),
                            latestApkUrl = "http://example.test/app.apk",
                            apkAssetName = "app.apk",
                            apkSizeBytes = 12L,
                            releasePublishedAt = "2024-01-02T15:04:05Z",
                            releaseTitle = "January drop",
                        ),
                    )
                }
            },
        )
        val result = checker.checkForUpdate(
            context = context,
            request = VersionCheckRequest(
                appName = GeoVaultAndroidReleaseIdentity.Uploader.WORKER_APP_NAME,
                localFullCommitSha = "d".repeat(40),
            ),
            cacheKey = "uploader",
        )
        assertTrue(result is VersionCheckResult.CheckFailed)
        assertEquals(
            "Release worker returned a non-HTTPS APK download URL",
            (result as VersionCheckResult.CheckFailed).detail,
        )
    }
}
