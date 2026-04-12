package com.geovault.tracker.presentation

import com.geovault.tracker.AppError
import com.geovault.tracker.RepositoryResult

sealed class TrackersGroupsMutationResult<out T> {
    data class Success<T>(val data: T) : TrackersGroupsMutationResult<T>()
    data class Failure(val error: AppError) : TrackersGroupsMutationResult<Nothing>()
}

object TrackersGroupsMutationCoordinator {
    suspend fun <T> run(
        mutation: suspend () -> RepositoryResult<T>
    ): TrackersGroupsMutationResult<T> {
        return when (val result = mutation()) {
            is RepositoryResult.Success -> TrackersGroupsMutationResult.Success(result.data)
            is RepositoryResult.Failure -> TrackersGroupsMutationResult.Failure(result.error)
        }
    }
}
