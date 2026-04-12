package com.geovault.tracker.presentation

import com.geovault.tracker.Group
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
        assertEquals(null, request.shared_with_emails)
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

    @Test
    fun buildPreservingPatchRequest_carriesForwardExistingGroupValues() {
        val group = Group(
            id = "g1",
            name = "Group A",
            hidden = true,
            visibility = "shared",
            shared_with_emails = listOf("a@example.com"),
            world_share_id = "wg-1",
        )
        val request = GroupSharingSettingsPolicy.buildPreservingPatchRequest(group)
        assertEquals("Group A", request.name)
        assertEquals(true, request.hidden)
        assertEquals("shared", request.visibility)
        assertEquals(listOf("a@example.com"), request.shared_with_emails)
        assertEquals(true, request.world_share_enabled)
    }

    @Test
    fun buildWorldShareTogglePatch_preservesNonTargetFields() {
        val group = Group(
            id = "g1",
            name = "Group A",
            hidden = true,
            visibility = "public",
            shared_with_emails = listOf("a@example.com"),
            world_share_id = "wg-1",
        )
        val request = GroupSharingSettingsPolicy.buildWorldShareTogglePatch(group, enabling = false)
        assertEquals("Group A", request.name)
        assertEquals(true, request.hidden)
        assertEquals("public", request.visibility)
        assertEquals(null, request.shared_with_emails)
        assertEquals(false, request.world_share_enabled)
    }

    @Test
    fun buildUnhidePatch_setsHiddenFalseAndPreservesOtherFields() {
        val group = Group(
            id = "g1",
            name = "Group A",
            hidden = true,
            visibility = "public",
            shared_with_emails = listOf("a@example.com"),
            world_share_id = "wg-1",
        )
        val request = GroupSharingSettingsPolicy.buildUnhidePatch(group)
        assertEquals("Group A", request.name)
        assertEquals(false, request.hidden)
        assertEquals("public", request.visibility)
        assertEquals(null, request.shared_with_emails)
        assertEquals(true, request.world_share_enabled)
    }
}
