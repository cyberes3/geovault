package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerSettingsRequest

enum class TrackerShareVisibility(val apiValue: String) {
    PRIVATE("private"),
    SHARED("shared"),
    PUBLIC("public");

    companion object {
        fun fromApiValue(raw: String?): TrackerShareVisibility {
            return entries.firstOrNull { it.apiValue == raw?.trim()?.lowercase() } ?: PRIVATE
        }
    }
}

/**
 * Visibility for the edit UI when [Tracker.visibility] is missing or unknown on the client
 * (e.g. list payload quirks). Uses explicit API value when it matches; otherwise infers from
 * share recipients or world-share fields.
 */
fun Tracker.shareVisibilityForEditing(): TrackerShareVisibility {
    val normalized = visibility?.trim()?.lowercase()
    TrackerShareVisibility.entries.firstOrNull { it.apiValue == normalized }?.let { return it }
    if (!shared_with_emails.isNullOrEmpty()) return TrackerShareVisibility.SHARED
    if (!world_share_id.isNullOrBlank() || !world_share_url.isNullOrBlank()) {
        return TrackerShareVisibility.PUBLIC
    }
    return TrackerShareVisibility.PRIVATE
}

data class TrackerSharingDraft(
    val visibility: TrackerShareVisibility,
    val sharedEmailsInput: String,
    val worldShareEnabled: Boolean
)

data class TrackerSharingValidationResult(
    val isValid: Boolean,
    val normalizedEmails: List<String>,
)

object TrackerSharingSettingsPolicy {
    fun parseSharedEmails(raw: String): List<String> {
        return raw
            .split(",", ";", "\n")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun validate(draft: TrackerSharingDraft): TrackerSharingValidationResult {
        val emails = parseSharedEmails(draft.sharedEmailsInput)
        return TrackerSharingValidationResult(
            isValid = true,
            normalizedEmails = emails
        )
    }

    fun buildSettingsRequest(
        name: String,
        sharingDraft: TrackerSharingDraft
    ): TrackerSettingsRequest {
        val normalized = validate(sharingDraft).normalizedEmails
        return TrackerSettingsRequest(
            name = name,
            visibility = sharingDraft.visibility.apiValue,
            shared_with_emails = if (sharingDraft.visibility == TrackerShareVisibility.SHARED) {
                normalized
            } else {
                emptyList()
            },
            world_share_enabled = sharingDraft.visibility != TrackerShareVisibility.PRIVATE &&
                sharingDraft.worldShareEnabled
        )
    }
}
