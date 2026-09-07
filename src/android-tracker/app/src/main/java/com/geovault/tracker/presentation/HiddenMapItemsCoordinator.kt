package com.geovault.tracker.presentation

import com.geovault.common.coroutines.runSuspendCatching
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class HiddenMapItemsSnapshot(
    val mapVisibility: MapVisibilityResponse,
    val trackers: List<Tracker>,
    val groups: List<Group>,
    val warning: GeoVaultApiFailure? = null,
)

object HiddenMapItemsCoordinator {
    suspend fun loadSnapshot(
        forceRefresh: Boolean,
        loadMapVisibility: suspend (Boolean) -> MapVisibilityResponse,
        loadTrackers: suspend (Boolean) -> List<Tracker>,
        loadGroups: suspend (Boolean) -> List<Group>,
    ): HiddenMapItemsSnapshot {
        val (mapVisibility, trackersResult, groupsResult) = coroutineScope {
            val visibilityDeferred = async { loadMapVisibility(forceRefresh) }
            val trackersDeferred = async { runSuspendCatching { loadTrackers(forceRefresh) } }
            val groupsDeferred = async { runSuspendCatching { loadGroups(forceRefresh) } }
            Triple(
                visibilityDeferred.await(),
                trackersDeferred.await(),
                groupsDeferred.await(),
            )
        }
        val trackersError = trackersResult.exceptionOrNull()
        if (trackersError != null && trackersError !is GeoVaultApiFailure) throw trackersError
        val groupsError = groupsResult.exceptionOrNull()
        if (groupsError != null && groupsError !is GeoVaultApiFailure) throw groupsError
        return HiddenMapItemsSnapshot(
            mapVisibility = mapVisibility,
            trackers = trackersResult.getOrDefault(emptyList()),
            groups = groupsResult.getOrDefault(emptyList()),
            warning = (trackersError as? GeoVaultApiFailure) ?: (groupsError as? GeoVaultApiFailure),
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
}
