package com.geovault.tracker.presentation

import com.geovault.common.net.GeoVaultApiFailure

sealed class TrackersGroupsMutationResult<out T> {
    data class Success<T>(val data: T) : TrackersGroupsMutationResult<T>()
    data class Failure(val error: GeoVaultApiFailure) : TrackersGroupsMutationResult<Nothing>()
}

object TrackersGroupsMutationCoordinator {
    suspend fun <T> run(
        mutation: suspend () -> T
    ): TrackersGroupsMutationResult<T> {
        return try {
            TrackersGroupsMutationResult.Success(mutation())
        } catch (e: GeoVaultApiFailure) {
            TrackersGroupsMutationResult.Failure(e)
        }
    }
}
