package com.geovault.tracker.presentation

import com.geovault.common.net.GeoVaultApiFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackersGroupsMutationCoordinatorTest {

    @Test
    fun run_mapsSuccessResult() = runBlocking {
        val result = TrackersGroupsMutationCoordinator.run {
            "ok"
        }

        assertTrue(result is TrackersGroupsMutationResult.Success)
        assertEquals("ok", (result as TrackersGroupsMutationResult.Success).data)
    }

    @Test
    fun run_mapsFailureResult() = runBlocking {
        val failure = GeoVaultApiFailure(httpCode = null, serverMessage = "network")
        val result = TrackersGroupsMutationCoordinator.run<String> {
            throw failure
        }

        assertEquals(
            TrackersGroupsMutationResult.Failure(failure),
            result
        )
    }
}
