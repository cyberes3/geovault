package com.geovault.tracker.presentation

import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

data class HiddenMapItemsSnapshot(
    val mapVisibility: MapVisibilityResponse,
    val trackers: List<Tracker>,
    val groups: List<Group>,
    val warning: AppError? = null,
)

object HiddenMapItemsCoordinator {
    suspend fun loadSnapshot(
        forceRefresh: Boolean,
        loadMapVisibility: suspend (Boolean) -> RepositoryResult<MapVisibilityResponse>,
        loadTrackers: suspend (Boolean) -> RepositoryResult<List<Tracker>>,
        loadGroups: suspend (Boolean) -> RepositoryResult<List<Group>>,
    ): RepositoryResult<HiddenMapItemsSnapshot> {
        val mapVisibility = when (val loaded = loadMapVisibility(forceRefresh)) {
            is RepositoryResult.Success -> loaded.data
            is RepositoryResult.Failure -> return RepositoryResult.Failure(loaded.error)
        }
        val trackersResult = loadTrackers(forceRefresh)
        val groupsResult = loadGroups(forceRefresh)
        val warning = firstError(trackersResult, groupsResult)
        return RepositoryResult.Success(
            HiddenMapItemsSnapshot(
                mapVisibility = mapVisibility,
                trackers = trackersResult.successDataOrEmpty(),
                groups = groupsResult.successDataOrEmpty(),
                warning = warning
            )
        )
    }

    fun buildHiddenItems(snapshot: HiddenMapItemsSnapshot): List<HiddenMapItem> {
        return HiddenMapItemsPolicy.buildHiddenItems(
            mapVisibility = snapshot.mapVisibility,
            trackers = snapshot.trackers,
            groups = snapshot.groups
        )
    }

    fun buildUnhideItemRequest(
        mapVisibility: MapVisibilityResponse,
        item: HiddenMapItem
    ): MapVisibilityRequest {
        return when (item.type) {
            HiddenMapItemType.TRACKER -> MapVisibilityRequest(
                hidden_track_ids = mapVisibility.hidden_track_ids.filterNot { it == item.id },
                hidden_group_ids = mapVisibility.hidden_group_ids
            )
            HiddenMapItemType.GROUP -> MapVisibilityRequest(
                hidden_track_ids = mapVisibility.hidden_track_ids,
                hidden_group_ids = mapVisibility.hidden_group_ids.filterNot { it == item.id }
            )
        }
    }

    fun buildUnhideAllRequest(): MapVisibilityRequest {
        return MapVisibilityRequest(hidden_track_ids = emptyList(), hidden_group_ids = emptyList())
    }

    private fun firstError(vararg results: RepositoryResult<*>): AppError? {
        for (result in results) {
            if (result is RepositoryResult.Failure) return result.error
        }
        return null
    }

    private fun <T> RepositoryResult<List<T>>.successDataOrEmpty(): List<T> {
        return when (this) {
            is RepositoryResult.Success -> data
            is RepositoryResult.Failure -> emptyList()
        }
    }
}
