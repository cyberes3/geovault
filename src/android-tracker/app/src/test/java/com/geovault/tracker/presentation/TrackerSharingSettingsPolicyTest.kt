package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSharingSettingsPolicyTest {

    @Test
    fun validate_sharedAllowsEmptyEmails() {
        val result = TrackerSharingSettingsPolicy.validate(
            TrackerSharingDraft(
                visibility = TrackerShareVisibility.SHARED,
                sharedEmailsInput = " , ; ",
                worldShareEnabled = false
            )
        )
        assertTrue(result.isValid)
    }

    @Test
    fun parseSharedEmails_normalizesAndDedupes() {
        val emails = TrackerSharingSettingsPolicy.parseSharedEmails(
            " One@Example.com, two@example.com ; one@example.com\nTHREE@example.com "
        )
        assertEquals(listOf("one@example.com", "two@example.com", "three@example.com"), emails)
    }

    @Test
    fun buildSettingsRequest_publicUsesWorldShareFlag() {
        val request = TrackerSharingSettingsPolicy.buildSettingsRequest(
            name = "Tracker",
            sharingDraft = TrackerSharingDraft(
                visibility = TrackerShareVisibility.PUBLIC,
                sharedEmailsInput = "a@example.com",
                worldShareEnabled = true
            )
        )
        assertEquals("public", request.visibility)
        assertEquals(true, request.world_share_enabled)
        assertEquals(null, request.shared_with_emails)
    }

    @Test
    fun buildSettingsRequest_sharedUsesNormalizedEmails() {
        val request = TrackerSharingSettingsPolicy.buildSettingsRequest(
            name = "Tracker",
            sharingDraft = TrackerSharingDraft(
                visibility = TrackerShareVisibility.SHARED,
                sharedEmailsInput = "a@example.com, b@example.com",
                worldShareEnabled = false
            )
        )
        assertEquals("shared", request.visibility)
        assertEquals(listOf("a@example.com", "b@example.com"), request.shared_with_emails)
        assertTrue(request.world_share_enabled == false || request.world_share_enabled == null)
    }

    @Test
    fun buildSettingsRequest_sharedAllowsWorldShare() {
        val request = TrackerSharingSettingsPolicy.buildSettingsRequest(
            name = "Tracker",
            sharingDraft = TrackerSharingDraft(
                visibility = TrackerShareVisibility.SHARED,
                sharedEmailsInput = "a@example.com",
                worldShareEnabled = true
            )
        )
        assertEquals("shared", request.visibility)
        assertEquals(true, request.world_share_enabled)
        assertEquals(listOf("a@example.com"), request.shared_with_emails)
    }

    @Test
    fun buildSettingsRequest_privateDisablesWorldShare() {
        val request = TrackerSharingSettingsPolicy.buildSettingsRequest(
            name = "Tracker",
            sharingDraft = TrackerSharingDraft(
                visibility = TrackerShareVisibility.PRIVATE,
                sharedEmailsInput = "",
                worldShareEnabled = true
            )
        )
        assertEquals("private", request.visibility)
        assertEquals(false, request.world_share_enabled)
    }

    @Test
    fun buildPreservingSettingsRequest_carriesForwardExistingTrackerValues() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker One",
            color = "#112233",
            settings = mapOf(
                "recent_data_window" to "1h",
                "hidden" to true,
                "allow_group_reshare" to true,
            ),
            visibility = "shared",
            share_params_with_recipients = true,
            share_params_with_world = false,
            shared_with_emails = listOf("a@example.com"),
            world_share_id = "ws-1",
        )
        val request = TrackerSharingSettingsPolicy.buildPreservingSettingsRequest(tracker)

        assertEquals("Tracker One", request.name)
        assertEquals("#112233", request.color)
        assertEquals("1h", request.recent_data_window)
        assertEquals("shared", request.visibility)
        assertEquals(true, request.share_params_with_recipients)
        assertEquals(false, request.share_params_with_world)
        assertEquals(listOf("a@example.com"), request.shared_with_emails)
        assertEquals(true, request.world_share_enabled)
        assertEquals(true, request.hidden)
        assertEquals(true, request.allow_group_reshare)
    }

    @Test
    fun buildPreservingSettingsRequest_allowsTargetedOverrides() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker One",
            color = "#112233",
            settings = mapOf("hidden" to true),
            visibility = "public",
        )
        val request = TrackerSharingSettingsPolicy.buildPreservingSettingsRequest(
            tracker = tracker,
            hidden = false,
            worldShareEnabled = false,
            visibility = "public",
        )
        assertEquals(false, request.hidden)
        assertEquals(false, request.world_share_enabled)
        assertEquals("public", request.visibility)
    }

    @Test
    fun shareVisibilityForEditing_usesExplicitApiValue() {
        val t = Tracker(
            id = "1",
            name = "A",
            color = null,
            visibility = "shared",
        )
        assertEquals(TrackerShareVisibility.SHARED, t.shareVisibilityForEditing())
    }

    @Test
    fun shareVisibilityForEditing_infersSharedWhenVisibilityMissingButHasRecipients() {
        val t = Tracker(
            id = "1",
            name = "A",
            color = null,
            visibility = null,
            shared_with_emails = listOf("a@example.com"),
        )
        assertEquals(TrackerShareVisibility.SHARED, t.shareVisibilityForEditing())
    }

    @Test
    fun shareVisibilityForEditing_infersPublicWhenVisibilityMissingButWorldShare() {
        val t = Tracker(
            id = "1",
            name = "A",
            color = null,
            visibility = null,
            world_share_id = "ws1",
        )
        assertEquals(TrackerShareVisibility.PUBLIC, t.shareVisibilityForEditing())
    }
}
