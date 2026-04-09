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
        val isSharedVisibility = sharingDraft.visibility == TrackerShareVisibility.SHARED
        return TrackerSettingsRequest(
            name = name,
            visibility = sharingDraft.visibility.apiValue,
            shared_with_emails = if (isSharedVisibility) {
                normalized
            } else {
                null
            },
            world_share_enabled = sharingDraft.visibility != TrackerShareVisibility.PRIVATE &&
                sharingDraft.worldShareEnabled
        )
    }

    /**
     * Build a tracker settings request using current tracker state as baseline so non-target fields
     * are preserved and do not get reset by null serialization.
     */
    fun buildPreservingSettingsRequest(
        tracker: Tracker,
        name: String = tracker.name,
        color: String? = tracker.color,
        recentDataWindow: String? = tracker.settingsString("recent_data_window"),
        visibility: String? = tracker.visibility,
        shareParamsWithRecipients: Boolean? = tracker.share_params_with_recipients,
        shareParamsWithWorld: Boolean? = tracker.share_params_with_world,
        sharedWithEmails: List<String>? = tracker.shared_with_emails,
        worldShareEnabled: Boolean = isWorldShareEnabled(tracker),
        hidden: Boolean? = tracker.settingsBoolean("hidden"),
        allowGroupReshare: Boolean? = tracker.settingsBoolean("allow_group_reshare"),
    ): TrackerSettingsRequest {
        val effectiveVisibility = visibility?.trim()?.lowercase()
        val sharedEmailsForRequest = if (effectiveVisibility == TrackerShareVisibility.SHARED.apiValue) {
            sharedWithEmails
        } else {
            null
        }
        return TrackerSettingsRequest(
            name = name,
            color = color,
            recent_data_window = recentDataWindow,
            visibility = visibility,
            share_params_with_recipients = shareParamsWithRecipients,
            share_params_with_world = shareParamsWithWorld,
            shared_with_emails = sharedEmailsForRequest,
            world_share_enabled = worldShareEnabled,
            hidden = hidden,
            allow_group_reshare = allowGroupReshare,
        )
    }

    fun isWorldShareEnabled(tracker: Tracker): Boolean {
        return !tracker.world_share_id.isNullOrBlank() || !tracker.world_share_url.isNullOrBlank()
    }
}

private fun Tracker.settingsString(key: String): String? = settings?.get(key) as? String

private fun Tracker.settingsBoolean(key: String): Boolean? = settings?.get(key) as? Boolean
