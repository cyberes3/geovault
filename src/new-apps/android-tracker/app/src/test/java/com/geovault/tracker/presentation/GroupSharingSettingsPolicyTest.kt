package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSharingSettingsPolicyTest {

    @Test
    fun validate_sharedAllowsEmptyEmails() {
        val result = GroupSharingSettingsPolicy.validate(
            GroupSharingDraft(
                visibility = GroupShareVisibility.SHARED,
                sharedEmailsInput = " ; ",
                worldShareEnabled = false
            )
        )
        assertTrue(result.isValid)
    }

    @Test
    fun buildPatchRequest_publicUsesWorldShareFlag() {
        val request = GroupSharingSettingsPolicy.buildPatchRequest(
            name = "Ops",
            sharingDraft = GroupSharingDraft(
                visibility = GroupShareVisibility.PUBLIC,
                sharedEmailsInput = "",
                worldShareEnabled = true
            )
        )
        assertEquals("Ops", request.name)
        assertEquals("public", request.visibility)
        assertEquals(true, request.world_share_enabled)
        assertEquals(emptyList<String>(), request.shared_with_emails)
    }

    @Test
    fun buildPatchRequest_sharedAllowsWorldShare() {
        val request = GroupSharingSettingsPolicy.buildPatchRequest(
            name = "Team",
            sharingDraft = GroupSharingDraft(
                visibility = GroupShareVisibility.SHARED,
                sharedEmailsInput = "a@example.com",
                worldShareEnabled = true
            )
        )
        assertEquals("shared", request.visibility)
        assertEquals(true, request.world_share_enabled)
        assertEquals(listOf("a@example.com"), request.shared_with_emails)
    }

    @Test
    fun buildPatchRequest_privateDisablesWorldShare() {
        val request = GroupSharingSettingsPolicy.buildPatchRequest(
            name = "Team",
            sharingDraft = GroupSharingDraft(
                visibility = GroupShareVisibility.PRIVATE,
                sharedEmailsInput = "",
                worldShareEnabled = true
            )
        )
        assertEquals("private", request.visibility)
        assertEquals(false, request.world_share_enabled)
    }
}
