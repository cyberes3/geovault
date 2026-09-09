package com.geovault.tracker.presentation

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse

enum class MapVisibilityToggleEntityType {
    Tracker,
    Group,
}

data class MapVisibilityToggleTarget(
    val id: String,
    val type: MapVisibilityToggleEntityType
)

sealed class MapVisibilityMutationResult {
    data class Success(val visibility: MapVisibilityResponse) : MapVisibilityMutationResult()
    data class Failure(val error: GeoVaultApiFailure) : MapVisibilityMutationResult()
}

object MapVisibilityTogglePolicy {
    fun toggleTracker(current: MapVisibilityResponse, trackerId: String): MapVisibilityRequest {
        val hidden = current.hidden_track_ids.toMutableSet()
        if (hidden.contains(trackerId)) hidden.remove(trackerId) else hidden.add(trackerId)
        return MapVisibilityRequest(
            hidden_track_ids = hidden.toList(),
            hidden_group_ids = current.hidden_group_ids,
        )
    }

    fun toggleGroup(current: MapVisibilityResponse, groupId: String): MapVisibilityRequest {
        val hidden = current.hidden_group_ids.toMutableSet()
        if (hidden.contains(groupId)) hidden.remove(groupId) else hidden.add(groupId)
        return MapVisibilityRequest(
            hidden_track_ids = current.hidden_track_ids,
            hidden_group_ids = hidden.toList(),
        )
    }

    suspend fun toggle(
        current: MapVisibilityResponse?,
        target: MapVisibilityToggleTarget,
        loadVisibility: suspend () -> MapVisibilityResponse,
        patchVisibility: suspend (MapVisibilityRequest) -> MapVisibilityResponse,
    ): MapVisibilityMutationResult {
        val base = try {
            current ?: loadVisibility()
        } catch (e: GeoVaultApiFailure) {
            return MapVisibilityMutationResult.Failure(e)
        }
        val request = when (target.type) {
            MapVisibilityToggleEntityType.Tracker -> toggleTracker(base, target.id)
            MapVisibilityToggleEntityType.Group -> toggleGroup(base, target.id)
        }
        return try {
            MapVisibilityMutationResult.Success(patchVisibility(request))
        } catch (e: GeoVaultApiFailure) {
            MapVisibilityMutationResult.Failure(e)
        }
    }
}
