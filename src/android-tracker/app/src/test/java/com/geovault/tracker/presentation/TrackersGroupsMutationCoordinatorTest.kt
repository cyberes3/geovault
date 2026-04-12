package com.geovault.tracker.presentation

import com.geovault.tracker.AppError
import com.geovault.tracker.RepositoryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackersGroupsMutationCoordinatorTest {

    @Test
    fun run_mapsSuccessResult() = runBlocking {
        val result = TrackersGroupsMutationCoordinator.run {
            RepositoryResult.Success("ok")
        }

        assertTrue(result is TrackersGroupsMutationResult.Success)
        assertEquals("ok", (result as TrackersGroupsMutationResult.Success).data)
    }

    @Test
    fun run_mapsFailureResult() = runBlocking {
        val result = TrackersGroupsMutationCoordinator.run<String> {
            RepositoryResult.Failure(AppError.Network)
        }

        assertEquals(
            TrackersGroupsMutationResult.Failure(AppError.Network),
            result
        )
    }
}
