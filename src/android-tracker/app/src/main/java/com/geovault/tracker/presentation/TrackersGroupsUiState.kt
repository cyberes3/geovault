package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import com.geovault.tracker.UserItem

enum class TrackersGroupsSubTab {
    TRACKERS,
    GROUPS,
}

sealed interface TrackersGroupsDialog {
    data object Hidden : TrackersGroupsDialog
    data class EditTrackerLoading(
        val trackerId: String,
        val trackerName: String,
    ) : TrackersGroupsDialog
    data class CreateTracker(
        val nameDraft: String = "",
        val colorDraft: String = "",
        val setAsSelectedTracker: Boolean = false
    ) : TrackersGroupsDialog
    data class CreateGroup(val nameDraft: String = "") : TrackersGroupsDialog
    data class EditTracker(
        val tracker: Tracker,
        val nameDraft: String,
        val colorDraft: String,
        val setAsSelectedTracker: Boolean = false,
        val hiddenDraft: Boolean = false,
        val recentDataWindowDraft: String = "all",
        val visibilityDraft: TrackerShareVisibility = TrackerShareVisibility.PRIVATE,
        val sharedEmailsDraft: String = "",
        val shareParamsWithRecipientsDraft: Boolean = false,
        val allowGroupReshareDraft: Boolean = false,
        val worldShareEnabledDraft: Boolean = false,
        val shareParamsWithWorldDraft: Boolean = false,
        val internalShareUrlDraft: String? = null,
        val worldShareUrlDraft: String? = null,
        val isWorldShareLinkLoading: Boolean = false,
    ) : TrackersGroupsDialog
    data class EditGroup(
        val group: Group,
        val nameDraft: String,
        val visibilityDraft: GroupShareVisibility = GroupShareVisibility.PRIVATE,
        val sharedEmailsDraft: String = "",
        val worldShareEnabledDraft: Boolean = false,
        val internalShareUrlDraft: String? = null,
        val worldShareUrlDraft: String? = null,
        val isWorldShareLinkLoading: Boolean = false,
        val hiddenDraft: Boolean = false,
        val memberTrackIds: Set<String> = emptySet(),
        val initialMemberTrackIds: Set<String> = emptySet(),
    ) : TrackersGroupsDialog
}

data class TrackersGroupsUiState(
    val subTab: TrackersGroupsSubTab = TrackersGroupsSubTab.TRACKERS,
    val trackers: List<Tracker> = emptyList(),
    val groups: List<Group> = emptyList(),
    val mapVisibility: MapVisibilityResponse? = null,
    val trackerSearchQuery: String = "",
    val groupSearchQuery: String = "",
    val selectedTrackerId: String = "",
    val shareRecipientSuggestions: List<String> = emptyList(),
    val shareRecipientUsers: List<UserItem> = emptyList(),
    val isShareRecipientSuggestionsLoading: Boolean = false,
    val isKmlExportLoading: Boolean = false,
    val isLoading: Boolean = false,
    val isPickerRefreshing: Boolean = false,
    val isPullRefreshing: Boolean = false,
    val hasCompletedInitialLoad: Boolean = false,
    val userMessage: String? = null,
    val dialog: TrackersGroupsDialog = TrackersGroupsDialog.Hidden,
    val addingTrackerIds: Set<String> = emptySet(),
)
