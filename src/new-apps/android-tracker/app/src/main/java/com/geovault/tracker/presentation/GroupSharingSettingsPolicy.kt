package com.geovault.tracker.presentation

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
        sharingDraft: GroupSharingDraft
    ): GroupPatchRequest {
        val normalized = validate(sharingDraft).normalizedEmails
        return GroupPatchRequest(
            name = name,
            visibility = sharingDraft.visibility.apiValue,
            shared_with_emails = if (sharingDraft.visibility == GroupShareVisibility.SHARED) {
                normalized
            } else {
                emptyList()
            },
            world_share_enabled = sharingDraft.visibility != GroupShareVisibility.PRIVATE &&
                sharingDraft.worldShareEnabled
        )
    }
}
