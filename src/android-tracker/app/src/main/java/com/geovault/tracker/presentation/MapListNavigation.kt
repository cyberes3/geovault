package com.geovault.tracker.presentation

enum class MapListNavigationDestination {
    TRACKERS,
    GROUPS,
    SHARED,
}

data class MapListNavigationTarget(
    val destination: MapListNavigationDestination,
    val trackerId: String? = null,
    val groupId: String? = null,
)
