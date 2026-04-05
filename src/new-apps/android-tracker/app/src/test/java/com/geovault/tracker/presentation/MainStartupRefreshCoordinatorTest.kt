package com.geovault.tracker.presentation

import com.geovault.tracker.AppError
import com.geovault.tracker.RepositoryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainStartupRefreshCoordinatorTest {

    @Test
    fun run_marksServerAccessibleWhenTrackersLoadSucceeds() = runBlocking {
        var loadTrackerCalls = 0
        var loadTrackerGeometryCalls = 0
        val outcome = MainStartupRefreshCoordinator.run(
            selectedTrackerId = "t1",
            loadTrackers = { RepositoryResult.Success(Unit) },
            loadGroups = { RepositoryResult.Success(Unit) },
            loadMapVisibility = { RepositoryResult.Success(Unit) },
            loadAvailableToAdd = { RepositoryResult.Success(Unit) },
            loadTracker = {
                loadTrackerCalls += 1
                RepositoryResult.Success(Unit)
            },
            loadTrackerGeometry = {
                loadTrackerGeometryCalls += 1
                RepositoryResult.Success(Unit)
            }
        )

        assertTrue(outcome.isServerAccessible)
        assertTrue(outcome.selectedTrackerPrefetchAttempted)
        assertEquals(1, loadTrackerCalls)
        assertEquals(1, loadTrackerGeometryCalls)
    }

    @Test
    fun run_marksServerInaccessibleWhenTrackersLoadFails() = runBlocking {
        var loadTrackerCalls = 0
        var loadTrackerGeometryCalls = 0
        val outcome = MainStartupRefreshCoordinator.run(
            selectedTrackerId = "",
            loadTrackers = { RepositoryResult.Failure(AppError.Network) },
            loadGroups = { RepositoryResult.Success(Unit) },
            loadMapVisibility = { RepositoryResult.Success(Unit) },
            loadAvailableToAdd = { RepositoryResult.Success(Unit) },
            loadTracker = {
                loadTrackerCalls += 1
                RepositoryResult.Success(Unit)
            },
            loadTrackerGeometry = {
                loadTrackerGeometryCalls += 1
                RepositoryResult.Success(Unit)
            }
        )

        assertFalse(outcome.isServerAccessible)
        assertFalse(outcome.selectedTrackerPrefetchAttempted)
        assertEquals(0, loadTrackerCalls)
        assertEquals(0, loadTrackerGeometryCalls)
    }
}
