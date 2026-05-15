package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAvailablePromptComposerTest {

    private val uploaderAppName = GeoVaultAndroidReleaseIdentity.Uploader.WORKER_APP_NAME

    private fun sampleUpdate(): VersionCheckResult.UpdateAvailable = VersionCheckResult.UpdateAvailable(
        appName = uploaderAppName,
        versionLabel = "v2",
        releaseUrl = "https://example.com/r",
        releaseTag = "t",
        releaseCommitSha = "a".repeat(40),
        localCommitSha = "b".repeat(40),
        apkDownloadUrl = "https://example.com/a.apk",
        apkAssetName = "a.apk",
        apkSizeBytes = 1000L,
        releasePublishedAtIso = "2024-01-01T00:00:00Z",
        releaseTitle = "Title",
    )

    @Test
    fun `snackbarModelOrNull returns model for UpdateAvailable`() {
        val result = sampleUpdate()
        val model = UpdateAvailablePromptComposer.snackbarModelOrNull(
            result,
            message = "Hello message",
            detailsActionLabel = "Details",
        )!!
        assertEquals("update-available", model.id)
        assertEquals("Hello message", model.message)
        val action = model.action!!
        assertEquals("Details", action.label)
        assertEquals(UpdateAvailablePromptComposer.ACTION_OPEN_UPDATE_DETAILS, action.actionId)
    }

    @Test
    fun `snackbarModelOrNull returns null for UpToDate`() {
        val result = VersionCheckResult.UpToDate(
            localCommitSha = "a".repeat(40),
            detail = "ok"
        )
        assertNull(
            UpdateAvailablePromptComposer.snackbarModelOrNull(
                result,
                message = "x",
                detailsActionLabel = "Details",
            )
        )
    }

    @Test
    fun `snackbarModelOrNull returns null for CheckFailed`() {
        val result = VersionCheckResult.CheckFailed("network")
        assertNull(
            UpdateAvailablePromptComposer.snackbarModelOrNull(
                result,
                message = "x",
                detailsActionLabel = "Details",
            )
        )
    }

    @Test
    fun `snackbarModelOrNull returns null for Throttled`() {
        val result = VersionCheckResult.Throttled("later", lastCheckedAtMs = null)
        assertNull(
            UpdateAvailablePromptComposer.snackbarModelOrNull(
                result,
                message = "x",
                detailsActionLabel = "Details",
            )
        )
    }
}
