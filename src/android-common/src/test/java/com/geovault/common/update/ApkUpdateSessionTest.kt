package com.geovault.common.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ApkUpdateSessionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun installClickDownloadsVerifiesAndMarksLaunched() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dest = File(tempFolder.root, "out.apk")
        var launched = false
        val session = ApkUpdateSession(
            scope = CoroutineScope(dispatcher),
            downloadUrl = "https://example.test/a.apk",
            knownTotalBytes = 8L,
            destination = dest,
            download = { _, _, _, _ -> Result.success(Unit) },
            verifyApk = { Result.success(Unit) },
            launchInstall = {
                launched = true
                Result.success(Unit)
            },
            classifyFailure = { it.message ?: "fail" },
            canRequestPackageInstalls = { true },
            openInstallSettings = { },
        )

        session.onInstallClick()
        advanceUntilIdle()

        assertTrue(launched)
        assertEquals(ApkDownloadState.InstallLaunched, session.state.value)
    }

    @Test
    fun verifyFailureBecomesFailedState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dest = File(tempFolder.root, "bad.apk")
        val session = ApkUpdateSession(
            scope = CoroutineScope(dispatcher),
            downloadUrl = "https://example.test/a.apk",
            knownTotalBytes = 8L,
            destination = dest,
            download = { _, _, _, _ -> Result.success(Unit) },
            verifyApk = { Result.failure(IllegalStateException("apk_signing_mismatch")) },
            launchInstall = { Result.success(Unit) },
            classifyFailure = { it.message ?: "fail" },
            canRequestPackageInstalls = { true },
            openInstallSettings = { },
        )

        session.onInstallClick()
        advanceUntilIdle()

        val failed = session.state.value as ApkDownloadState.Failed
        assertEquals("apk_signing_mismatch", failed.message)
    }

    @Test
    fun missingInstallPermissionOpensSettingsWithoutDownloading() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var downloaded = false
        var openedSettings = false
        val dest = File(tempFolder.root, "perm.apk")
        val session = ApkUpdateSession(
            scope = CoroutineScope(dispatcher),
            downloadUrl = "https://example.test/a.apk",
            knownTotalBytes = 8L,
            destination = dest,
            download = { _, _, _, _ ->
                downloaded = true
                Result.success(Unit)
            },
            verifyApk = { Result.success(Unit) },
            launchInstall = { Result.success(Unit) },
            classifyFailure = { it.message ?: "fail" },
            canRequestPackageInstalls = { false },
            openInstallSettings = { openedSettings = true },
        )

        session.onInstallClick()
        advanceUntilIdle()

        assertTrue(openedSettings)
        assertEquals(false, downloaded)
        assertEquals(ApkDownloadState.Idle(), session.state.value)
    }
}
