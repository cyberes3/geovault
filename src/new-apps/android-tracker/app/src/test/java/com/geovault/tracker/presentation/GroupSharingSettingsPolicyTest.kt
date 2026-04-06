package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun buildPatchRequest_includesHiddenFlag() {
        val request = GroupSharingSettingsPolicy.buildPatchRequest(
            name = "G",
            sharingDraft = GroupSharingDraft(
                visibility = GroupShareVisibility.PRIVATE,
                sharedEmailsInput = "",
                worldShareEnabled = false
            ),
            hidden = true,
        )
        assertEquals(true, request.hidden)
    }

    @Test
    fun buildPatchRequest_includesMembershipDiffs() {
        val request = GroupSharingSettingsPolicy.buildPatchRequest(
            name = "G",
            sharingDraft = GroupSharingDraft(
                visibility = GroupShareVisibility.PRIVATE,
                sharedEmailsInput = "",
                worldShareEnabled = false
            ),
            addTrackIds = listOf("t1", "t2"),
            removeTrackIds = listOf("t3"),
        )
        assertEquals(listOf("t1", "t2"), request.add_track_ids)
        assertEquals(listOf("t3"), request.remove_track_ids)
    }

    @Test
    fun buildPatchRequest_omitsMembershipDiffsWhenEmpty() {
        val request = GroupSharingSettingsPolicy.buildPatchRequest(
            name = "G",
            sharingDraft = GroupSharingDraft(
                visibility = GroupShareVisibility.PRIVATE,
                sharedEmailsInput = "",
                worldShareEnabled = false
            ),
        )
        assertNull(request.add_track_ids)
        assertNull(request.remove_track_ids)
    }
}
