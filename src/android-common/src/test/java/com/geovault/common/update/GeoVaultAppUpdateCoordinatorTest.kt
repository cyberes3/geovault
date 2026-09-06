package com.geovault.common.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultAppUpdateCoordinatorTest {

    private val localSha = "a".repeat(40)

    @Test
    fun loggedOutDoesNotCheckOrEmitAvailable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var peekCalls = 0
        var checkCalls = 0
        val coordinator = coordinator(
            ioDispatcher = dispatcher,
            isLoggedIn = { false },
            peekCachedUpdate = { _, _ ->
                peekCalls += 1
                sampleUpdate()
            },
            checkForUpdate = { _, _ ->
                checkCalls += 1
                VersionCheckResult.CheckFailed("should not run")
            },
        )
        val emissions = collectPrompt(scope, coordinator)

        coordinator.launchIfNeeded(scope)
        scope.advanceUntilIdle()

        assertEquals(0, peekCalls)
        assertEquals(0, checkCalls)
        assertEquals(listOf(UpdatePromptState.Hidden), emissions)
    }

    @Test
    fun cachedOfferThenFailedLiveCheckDoesNotFlickerToHidden() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val cached = sampleUpdate(versionLabel = "cached")
        val coordinator = coordinator(
            ioDispatcher = dispatcher,
            peekCachedUpdate = { _, _ -> cached },
            checkForUpdate = { _, _ -> VersionCheckResult.CheckFailed("network") },
        )
        val emissions = collectPrompt(scope, coordinator)

        coordinator.launchIfNeeded(scope)
        scope.advanceUntilIdle()

        assertEquals(
            listOf(
                UpdatePromptState.Hidden,
                UpdatePromptState.Available(cached),
            ),
            emissions,
        )
        assertEquals(UpdatePromptState.Available(cached), coordinator.promptState.value)
    }

    @Test
    fun liveUpToDateHidesCachedOffer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val cached = sampleUpdate()
        val coordinator = coordinator(
            ioDispatcher = dispatcher,
            peekCachedUpdate = { _, _ -> cached },
            checkForUpdate = { _, _ ->
                VersionCheckResult.UpToDate(
                    localCommitSha = localSha,
                    detail = "current",
                )
            },
        )
        val emissions = collectPrompt(scope, coordinator)

        coordinator.launchIfNeeded(scope)
        scope.advanceUntilIdle()

        assertEquals(
            listOf(
                UpdatePromptState.Hidden,
                UpdatePromptState.Available(cached),
                UpdatePromptState.Hidden,
            ),
            emissions,
        )
    }

    @Test
    fun liveUpdateAvailableEmitsOnceWhenPeekMisses() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val live = sampleUpdate(versionLabel = "live")
        val coordinator = coordinator(
            ioDispatcher = dispatcher,
            peekCachedUpdate = { _, _ -> null },
            checkForUpdate = { _, _ -> live },
        )
        val emissions = collectPrompt(scope, coordinator)

        coordinator.launchIfNeeded(scope)
        scope.advanceUntilIdle()

        assertEquals(
            listOf(
                UpdatePromptState.Hidden,
                UpdatePromptState.Available(live),
            ),
            emissions,
        )
    }

    @Test
    fun launchIfNeededIsIdempotentUntilReset() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var checkCalls = 0
        val coordinator = coordinator(
            ioDispatcher = dispatcher,
            peekCachedUpdate = { _, _ -> null },
            checkForUpdate = { _, _ ->
                checkCalls += 1
                sampleUpdate()
            },
        )

        coordinator.launchIfNeeded(scope)
        coordinator.launchIfNeeded(scope)
        scope.advanceUntilIdle()
        assertEquals(1, checkCalls)

        coordinator.reset()
        assertEquals(UpdatePromptState.Hidden, coordinator.promptState.value)

        coordinator.launchIfNeeded(scope)
        scope.advanceUntilIdle()
        assertEquals(2, checkCalls)
        assertTrue(coordinator.promptState.value is UpdatePromptState.Available)
    }

    @Test
    fun dismissPromptHidesWithoutAllowingRelaunchUntilReset() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var checkCalls = 0
        val coordinator = coordinator(
            ioDispatcher = dispatcher,
            peekCachedUpdate = { _, _ -> null },
            checkForUpdate = { _, _ ->
                checkCalls += 1
                sampleUpdate()
            },
        )

        coordinator.launchIfNeeded(scope)
        scope.advanceUntilIdle()
        coordinator.dismissPrompt()
        assertEquals(UpdatePromptState.Hidden, coordinator.promptState.value)

        coordinator.launchIfNeeded(scope)
        scope.advanceUntilIdle()
        assertEquals(1, checkCalls)
        assertEquals(UpdatePromptState.Hidden, coordinator.promptState.value)
    }

    private fun collectPrompt(
        scope: TestScope,
        coordinator: GeoVaultAppUpdateCoordinator,
    ): MutableList<UpdatePromptState> {
        val emissions = mutableListOf<UpdatePromptState>()
        scope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            coordinator.promptState.collect { emissions.add(it) }
        }
        return emissions
    }

    private fun coordinator(
        ioDispatcher: CoroutineDispatcher,
        isLoggedIn: () -> Boolean = { true },
        peekCachedUpdate: (String, String) -> VersionCheckResult.UpdateAvailable?,
        checkForUpdate: (String, VersionCheckRequest) -> VersionCheckResult,
    ): GeoVaultAppUpdateCoordinator = GeoVaultAppUpdateCoordinator(
        cacheKey = "tracker",
        releaseWorkerAppName = GeoVaultAndroidReleaseIdentity.Tracker.WORKER_APP_NAME,
        localFullCommitSha = { localSha },
        isLoggedIn = isLoggedIn,
        peekCachedUpdate = peekCachedUpdate,
        checkForUpdate = checkForUpdate,
        ioDispatcher = ioDispatcher,
    )

    private fun sampleUpdate(
        versionLabel: String = "v2",
    ): VersionCheckResult.UpdateAvailable = VersionCheckResult.UpdateAvailable(
        appName = GeoVaultAndroidReleaseIdentity.Tracker.WORKER_APP_NAME,
        versionLabel = versionLabel,
        releaseUrl = "https://example.test/r",
        releaseTag = "t",
        releaseCommitSha = "c".repeat(40),
        localCommitSha = localSha,
        apkDownloadUrl = "https://example.test/a.apk",
        apkAssetName = "a.apk",
        apkSizeBytes = 1000L,
        releasePublishedAtIso = "2024-01-01T00:00:00Z",
        releaseTitle = "Title",
    )
}
