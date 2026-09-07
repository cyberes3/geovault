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

object MapVisibilityMutationCoordinator {
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
            MapVisibilityToggleEntityType.Tracker ->
                MapVisibilityTogglePolicy.toggleTracker(base, target.id)
            MapVisibilityToggleEntityType.Group ->
                MapVisibilityTogglePolicy.toggleGroup(base, target.id)
        }
        return try {
            MapVisibilityMutationResult.Success(patchVisibility(request))
        } catch (e: GeoVaultApiFailure) {
            MapVisibilityMutationResult.Failure(e)
        }
    }
}
