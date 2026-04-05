package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GroupSharingSettingsPolicyTest {

    @Test
    fun validate_sharedRequiresEmails() {
        val result = GroupSharingSettingsPolicy.validate(
            GroupSharingDraft(
                visibility = GroupShareVisibility.SHARED,
                sharedEmailsInput = " ; ",
                worldShareEnabled = false
            )
        )
        assertFalse(result.isValid)
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
}
