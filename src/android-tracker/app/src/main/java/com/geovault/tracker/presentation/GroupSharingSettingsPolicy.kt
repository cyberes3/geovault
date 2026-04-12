package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest

enum class GroupShareVisibility(val apiValue: String) {
    PRIVATE("private"),
    SHARED("shared"),
    PUBLIC("public");

    companion object {
        fun fromApiValue(raw: String?): GroupShareVisibility {
            return entries.firstOrNull { it.apiValue == raw?.trim()?.lowercase() } ?: PRIVATE
        }
    }
}

data class GroupSharingDraft(
    val visibility: GroupShareVisibility,
    val sharedEmailsInput: String,
    val worldShareEnabled: Boolean
)

data class GroupSharingValidationResult(
    val isValid: Boolean,
    val normalizedEmails: List<String>,
)

object GroupSharingSettingsPolicy {
    fun validate(draft: GroupSharingDraft): GroupSharingValidationResult {
        val emails = TrackerSharingSettingsPolicy.parseSharedEmails(draft.sharedEmailsInput)
        return GroupSharingValidationResult(
            isValid = true,
            normalizedEmails = emails
        )
    }

    fun buildPatchRequest(
        name: String,
        sharingDraft: GroupSharingDraft,
        hidden: Boolean = false,
        addTrackIds: List<String> = emptyList(),
        removeTrackIds: List<String> = emptyList(),
    ): GroupPatchRequest {
        val normalized = validate(sharingDraft).normalizedEmails
        val isSharedVisibility = sharingDraft.visibility == GroupShareVisibility.SHARED
        return GroupPatchRequest(
            name = name,
            hidden = hidden,
            visibility = sharingDraft.visibility.apiValue,
            shared_with_emails = if (isSharedVisibility) {
                normalized
            } else {
                null
            },
            world_share_enabled = sharingDraft.visibility != GroupShareVisibility.PRIVATE &&
                sharingDraft.worldShareEnabled,
            add_track_ids = addTrackIds.ifEmpty { null },
            remove_track_ids = removeTrackIds.ifEmpty { null },
        )
    }

    fun buildPreservingPatchRequest(
        group: Group,
        name: String = group.name,
        hidden: Boolean = group.hidden == true,
        visibility: String? = group.visibility,
        sharedWithEmails: List<String>? = group.shared_with_emails,
        worldShareEnabled: Boolean = isWorldShareEnabled(group),
        addTrackIds: List<String> = emptyList(),
        removeTrackIds: List<String> = emptyList(),
    ): GroupPatchRequest {
        val effectiveVisibility = visibility?.trim()?.lowercase()
        val sharedEmailsForRequest = if (effectiveVisibility == GroupShareVisibility.SHARED.apiValue) {
            sharedWithEmails
        } else {
            null
        }
        return GroupPatchRequest(
            name = name,
            hidden = hidden,
            visibility = visibility,
            shared_with_emails = sharedEmailsForRequest,
            world_share_enabled = worldShareEnabled,
            add_track_ids = addTrackIds.ifEmpty { null },
            remove_track_ids = removeTrackIds.ifEmpty { null },
        )
    }

    fun buildWorldShareTogglePatch(group: Group, enabling: Boolean): GroupPatchRequest {
        return buildPreservingPatchRequest(
            group = group,
            worldShareEnabled = enabling,
        )
    }

    fun buildUnhidePatch(group: Group): GroupPatchRequest {
        return buildPreservingPatchRequest(
            group = group,
            hidden = false,
        )
    }

    fun isWorldShareEnabled(group: Group): Boolean {
        return !group.world_share_id.isNullOrBlank() || !group.world_share_url.isNullOrBlank()
    }
}
