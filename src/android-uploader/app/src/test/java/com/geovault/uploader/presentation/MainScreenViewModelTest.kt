package com.geovault.uploader.presentation

import android.app.Application
import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.FetchUserStatusResult
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.auth.GeoVaultConnectionStatus
import com.geovault.common.auth.GeoVaultConnectionValidator
import com.geovault.common.auth.ServerConfigService
import com.geovault.common.ui.model.GeoVaultStatusVisualState
import com.geovault.common.update.GeoVaultAppUpdateCoordinator
import com.geovault.common.update.GeoVaultAppUpdatePromptBinding
import com.geovault.common.update.VersionCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class MainScreenViewModelTest {
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
    fun login_validatesAndChecksVersion() {
        val vm = viewModel(
            status = FetchUserStatusResult(email = "user@example.test", isUserStatusEndpointReachable = true),
            update = sampleUpdate(),
        )
        vm.onAccountStateChanged(GeoVaultAccountUiState(isLoggedIn = true, userEmail = "user@example.test"))
        waitUntil {
            vm.state.value.status.visualState == GeoVaultStatusVisualState.Success &&
                vm.state.value.updateAvailable != null
        }
        val state = vm.state.value
        assertEquals(GeoVaultStatusVisualState.Success, state.status.visualState)
        assertTrue(state.status.message.contains("Share a file"))
        assertFalse(state.status.message.contains("API key", ignoreCase = true))
        assertNotNull(state.updateAvailable)
        assertEquals("v9", state.updateAvailable?.versionLabel)
    }

    @Test
    fun logout_clearsStatusAndPrompt() {
        val vm = viewModel(
            status = FetchUserStatusResult(email = "user@example.test", isUserStatusEndpointReachable = true),
            update = sampleUpdate(),
        )
        vm.onAccountStateChanged(GeoVaultAccountUiState(isLoggedIn = true))
        waitUntil { vm.state.value.updateAvailable != null }
        assertNotNull(vm.state.value.updateAvailable)
        vm.onAccountStateChanged(GeoVaultAccountUiState(isLoggedIn = false))
        assertEquals(MainScreenViewModel.notConfiguredStatus(), vm.state.value.status)
        assertNull(vm.state.value.updateAvailable)
    }

    @Test
    fun toStatusModel_usesSessionCopy() {
        val unauthorized = MainScreenViewModel.toStatusModel(GeoVaultConnectionStatus.Unauthorized)
        assertEquals(GeoVaultStatusVisualState.Error, unauthorized.visualState)
        assertTrue(unauthorized.message.contains("Reconnect in Settings"))
        assertFalse(unauthorized.message.contains("API key", ignoreCase = true))
        val connected = MainScreenViewModel.toStatusModel(
            GeoVaultConnectionStatus.Connected("user@example.test"),
        )
        assertEquals("Choose File", connected.primaryAction?.label)
        assertFalse(connected.message.contains("API key", ignoreCase = true))
    }

    private fun viewModel(
        status: FetchUserStatusResult,
        update: VersionCheckResult.UpdateAvailable?,
    ): MainScreenViewModel {
        val coordinator = GeoVaultAppUpdateCoordinator(
            cacheKey = "uploader-test",
            releaseWorkerAppName = "uploader",
            localFullCommitSha = { "a".repeat(40) },
            isLoggedIn = { true },
            peekCachedUpdate = { _, _ -> null },
            checkForUpdate = { _, _ -> update ?: VersionCheckResult.UpToDate(localCommitSha = "a".repeat(40), detail = "ok") },
            ioDispatcher = dispatcher,
        )
        return MainScreenViewModel(
            application = RuntimeEnvironment.getApplication(),
            connectionValidator = GeoVaultConnectionValidator(
                serverConfigService = FakeServerConfig(),
                authSessionService = FakeAuthSession(),
                fetchUserStatus = { status },
            ),
            updatePromptBinding = GeoVaultAppUpdatePromptBinding(coordinator),
        )
    }

    private fun sampleUpdate(): VersionCheckResult.UpdateAvailable {
        return VersionCheckResult.UpdateAvailable(
            appName = "uploader",
            versionLabel = "v9",
            releaseUrl = "https://example.test/r",
            releaseTag = "t",
            releaseCommitSha = "c".repeat(40),
            localCommitSha = "a".repeat(40),
            apkDownloadUrl = "https://example.test/a.apk",
            apkAssetName = "a.apk",
            apkSizeBytes = 1000L,
            releasePublishedAtIso = "2024-01-01T00:00:00Z",
            releaseTitle = "Title",
        )
    }

    private class FakeServerConfig : ServerConfigService {
        override fun getServerUrl(): String = "https://example.test"
        override fun setServerUrl(url: String) = Unit
        override fun normalizeServerUrl(url: String): String = url
        override fun getNormalizedServerUrl(): String = "https://example.test"
        override fun resolveServerUrlToCanonical(url: String) = Result.success(url)
    }

    private fun waitUntil(timeoutMs: Long = 2_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for condition" }
            Thread.sleep(10)
        }
    }

    private class FakeAuthSession : AuthSessionService {
        override fun isLoggedIn(): Boolean = true
        override fun getCachedUserEmail(): String? = "user@example.test"
        override fun fetchUserStatus(callback: (String?) -> Unit) = callback("user@example.test")
        override fun revokeCurrentSession() = Unit
        override fun handleAuthFailure() = Unit
    }
}
