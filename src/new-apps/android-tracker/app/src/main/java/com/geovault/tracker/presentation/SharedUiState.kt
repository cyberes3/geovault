package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker

enum class SharedSubTab {
    SHARED,
    DISCOVER,
    PUBLIC,
}

data class SharedUiState(
    val subTab: SharedSubTab = SharedSubTab.SHARED,
    val trackers: List<Tracker> = emptyList(),
    val groups: List<Group> = emptyList(),
    val availableToAdd: AvailableToAddResponse? = null,
    val mapVisibility: MapVisibilityResponse? = null,
    val isLoading: Boolean = false,
    val userMessage: String? = null,
    val hasCompletedInitialLoad: Boolean = false,
) {
    val visibleSharedTrackers: List<Tracker>
        get() = computeVisibleSharedTrackers(trackers, groups)

    val visibleSharedGroups: List<Group>
        get() = computeVisibleSharedGroups(groups)

    val incomingTrackers: List<AvailableToAddItem>
        get() = availableToAdd?.shared_with_me.orEmpty()

    val incomingGroups: List<AvailableToAddGroup>
        get() = availableToAdd?.shared_with_me_groups.orEmpty()

    val publicDiscoverTrackers: List<AvailableToAddItem>
        get() = availableToAdd?.public.orEmpty()

    val publicDiscoverGroups: List<AvailableToAddGroup>
        get() = availableToAdd?.public_groups.orEmpty()
}
