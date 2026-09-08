package com.geovault.places.presentation

import android.app.Application
import com.geovault.common.sync.GeoVaultRefreshTimeoutPolicy
import com.geovault.common.update.GeoVaultAppUpdateCoordinator
import com.geovault.common.update.GeoVaultAppUpdatePromptBinding
import com.geovault.common.update.VersionCheckResult
import com.geovault.places.FakePlacesRemote
import com.geovault.places.data.PlacesStore
import com.geovault.places.domain.ConflictResolutionPolicy
import com.geovault.places.domain.NavigationRetryFlusher
import com.geovault.places.domain.PlacesSyncEngine
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class PlacesShellViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refreshNowTimeoutCoversFullSyncIncludingFetch() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = PlacesStore(context, fileName = uniqueFile())
        store.preloadOnLaunch()
        val remote = FakePlacesRemote(
            onFetchPlaces = {
                delay(60_000)
                emptyList()
            },
        )
        val engine = PlacesSyncEngine(remote, store, ConflictResolutionPolicy(), NoopNav)
        val viewModel = PlacesShellViewModel(
            application = context,
            placesStore = store,
            syncEngine = engine,
            trackNavigation = {},
            updatePromptBinding = unusedUpdateBinding(),
        )

        viewModel.refreshNow()
        advanceTimeBy(GeoVaultRefreshTimeoutPolicy.DEFAULT_TIMEOUT_MS)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals("Refresh timed out (10s)", viewModel.state.value.snackbar?.message)
    }

    @Test
    fun cancelRefreshSurfacesCancelledMessage() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = PlacesStore(context, fileName = uniqueFile())
        store.preloadOnLaunch()
        val remote = FakePlacesRemote(
            onFetchPlaces = {
                delay(60_000)
                emptyList()
            },
        )
        val engine = PlacesSyncEngine(remote, store, ConflictResolutionPolicy(), NoopNav)
        val viewModel = PlacesShellViewModel(
            application = context,
            placesStore = store,
            syncEngine = engine,
            trackNavigation = {},
            updatePromptBinding = unusedUpdateBinding(),
        )

        viewModel.refreshNow()
        advanceTimeBy(1_000)
        viewModel.cancelRefresh()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRefreshing)
        assertTrue(
            viewModel.state.value.snackbar?.message == PlacesOfflineBehaviorPolicy.REFRESH_CANCELLED_USING_CACHE_MESSAGE,
        )
    }

    private fun unusedUpdateBinding(): GeoVaultAppUpdatePromptBinding {
        return GeoVaultAppUpdatePromptBinding(
            GeoVaultAppUpdateCoordinator(
                cacheKey = "places-test",
                releaseWorkerAppName = "places",
                localFullCommitSha = { "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" },
                isLoggedIn = { false },
                peekCachedUpdate = { _, _ -> null },
                checkForUpdate = { _, _ ->
                    VersionCheckResult.UpToDate(
                        localCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        detail = "ok",
                    )
                },
            ),
        )
    }

    private fun uniqueFile(): String = "places_shell_test_${UUID.randomUUID()}.settings"
}

private object NoopNav : NavigationRetryFlusher {
    override suspend fun flushPending() = Unit
}
