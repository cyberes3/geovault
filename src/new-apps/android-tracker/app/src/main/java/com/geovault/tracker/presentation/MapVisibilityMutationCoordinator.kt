package com.geovault.tracker.presentation

import com.geovault.tracker.AppError
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult

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
    data class Failure(val error: AppError) : MapVisibilityMutationResult()
}

object MapVisibilityMutationCoordinator {
    suspend fun toggle(
        current: MapVisibilityResponse?,
        target: MapVisibilityToggleTarget,
        loadVisibility: suspend () -> RepositoryResult<MapVisibilityResponse>,
        patchVisibility: suspend (MapVisibilityRequest) -> RepositoryResult<MapVisibilityResponse>,
    ): MapVisibilityMutationResult {
        val base = when {
            current != null -> current
            else -> when (val loaded = loadVisibility()) {
                is RepositoryResult.Success -> loaded.data
                is RepositoryResult.Failure -> return MapVisibilityMutationResult.Failure(loaded.error)
            }
        }
        val request = when (target.type) {
            MapVisibilityToggleEntityType.Tracker ->
                MapVisibilityTogglePolicy.toggleTracker(base, target.id)
            MapVisibilityToggleEntityType.Group ->
                MapVisibilityTogglePolicy.toggleGroup(base, target.id)
        }
        return when (val patched = patchVisibility(request)) {
            is RepositoryResult.Success -> MapVisibilityMutationResult.Success(patched.data)
            is RepositoryResult.Failure -> MapVisibilityMutationResult.Failure(patched.error)
        }
    }
}
