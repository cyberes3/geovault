package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker

enum class TrackersGroupsSubTab {
    TRACKERS,
    GROUPS,
}

sealed interface TrackersGroupsDialog {
    data object Hidden : TrackersGroupsDialog
    data class CreateTracker(val nameDraft: String = "", val colorDraft: String = "") : TrackersGroupsDialog
    data class CreateGroup(val nameDraft: String = "") : TrackersGroupsDialog
    data class EditTracker(val tracker: Tracker, val nameDraft: String) : TrackersGroupsDialog
    data class EditGroup(val group: Group, val nameDraft: String) : TrackersGroupsDialog
}

data class TrackersGroupsUiState(
    val subTab: TrackersGroupsSubTab = TrackersGroupsSubTab.TRACKERS,
    val trackers: List<Tracker> = emptyList(),
    val groups: List<Group> = emptyList(),
    val mapVisibility: MapVisibilityResponse? = null,
    val isLoading: Boolean = false,
    val userMessage: String? = null,
    val dialog: TrackersGroupsDialog = TrackersGroupsDialog.Hidden,
)
