package com.geovault.tracker.fragments

import com.geovault.tracker.AppError
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.TrackerListRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TrackersListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun load_success_updatesUiState() = runTest {
        val viewModel = TrackersListViewModel(
            trackerListRepository = object : TrackerListRepository {
                override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> {
                    return RepositoryResult.Success(
                        listOf(
                            Tracker(id = "1", name = "One", color = null),
                            Tracker(id = "2", name = "Two", color = null)
                        )
                    )
                }
            },
            stateStore = TrackerManagementStateStore()
        )

        viewModel.load(forceRefresh = true, showLoading = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.trackers.size)
        assertTrue(state.errorMessage == null)
    }

    @Test
    fun load_failure_setsErrorAndStopsLoading() = runTest {
        val viewModel = TrackersListViewModel(
            trackerListRepository = object : TrackerListRepository {
                override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> {
                    return RepositoryResult.Failure(AppError.Network)
                }
            },
            stateStore = TrackerManagementStateStore()
        )

        viewModel.load(forceRefresh = false, showLoading = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(0, state.trackers.size)
        assertTrue(state.errorMessage != null)
    }
}

