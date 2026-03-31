package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAvailablePromptComposerTest {

    @Test
    fun `snackbarModelOrNull returns model for UpdateAvailable`() {
        val result = VersionCheckResult.UpdateAvailable(
            appName = "GeoVault Uploader",
            versionLabel = "v2",
            releaseUrl = "https://example.com/r",
            releaseTag = "t",
            releaseCommitSha = "a".repeat(40),
            localCommitSha = "b".repeat(40)
        )
        val model = UpdateAvailablePromptComposer.snackbarModelOrNull(result)!!
        assertEquals("update-available", model.id)
        assertEquals(
            "A newer GeoVault Uploader version is available (v2).",
            model.message
        )
        val action = model.action!!
        assertEquals("Open", action.label)
        assertEquals(UpdateAvailablePromptComposer.ACTION_OPEN_RELEASE, action.actionId)
    }

    @Test
    fun `snackbarModelOrNull returns null for UpToDate`() {
        val result = VersionCheckResult.UpToDate(
            localCommitSha = "a".repeat(40),
            detail = "ok"
        )
        assertNull(UpdateAvailablePromptComposer.snackbarModelOrNull(result))
    }

    @Test
    fun `snackbarModelOrNull returns null for CheckFailed`() {
        val result = VersionCheckResult.CheckFailed("network")
        assertNull(UpdateAvailablePromptComposer.snackbarModelOrNull(result))
    }

    @Test
    fun `snackbarModelOrNull returns null for Throttled`() {
        val result = VersionCheckResult.Throttled("later", lastCheckedAtMs = null)
        assertNull(UpdateAvailablePromptComposer.snackbarModelOrNull(result))
    }
}
