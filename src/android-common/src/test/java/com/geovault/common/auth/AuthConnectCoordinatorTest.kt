package com.geovault.common.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class AuthConnectCoordinatorTest {

    @Test
    fun secondLaunchCancelsFirstAndOnlyLatestResultApplies() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val serverConfig = object : ServerConfigService {
            override fun getServerUrl(): String = ""
            override fun setServerUrl(url: String) = Unit
            override fun normalizeServerUrl(url: String): String = url
            override fun getNormalizedServerUrl(): String = ""
            override fun resolveServerUrlToCanonical(url: String): Result<String> {
                return Result.success(
                    if (url == "second") "https://fresh.example" else "https://stale.example"
                )
            }
        }
        val controller = CommonInitialAuthController(
            serverConfigService = serverConfig,
            authSessionService = FakeAuthSessionService(),
            oauthPreparationService = FakeOAuthPreparationService(),
            peerServerUrlsProvider = { emptySet() },
        )
        val coordinator = AuthConnectCoordinator(scope, controller)
        val results = mutableListOf<CommonInitialAuthController.OAuthPreparationResult>()
        var connectingCount = 0

        coordinator.launch(
            rawServerUrl = "first",
            onConnecting = { connectingCount++ },
            onResult = { results.add(it) },
        )
        coordinator.launch(
            rawServerUrl = "second",
            onConnecting = { connectingCount++ },
            onResult = { results.add(it) },
        )
        scope.advanceUntilIdle()

        assertEquals(2, connectingCount)
        assertEquals(1, results.size)
        val ready = results.single() as CommonInitialAuthController.OAuthPreparationResult.Ready
        assertTrue(ready.oauthUrl.contains("https://fresh.example"))
    }

    private class FakeOAuthPreparationService : OAuthPreparationService {
        override fun generatePkcePair(): Pair<String, String> = "verifier" to "challenge"
        override fun generateOAuthStateNonce(length: Int): String = "b".repeat(length)
        override fun savePkceState(verifier: String, state: String) = Unit
        override fun buildAuthorizeUrl(serverUrl: String, codeChallenge: String, state: String): String {
            return "$serverUrl/oauth?state=$state"
        }
    }

    private class FakeAuthSessionService : AuthSessionService {
        override fun isLoggedIn(): Boolean = false
        override fun getCachedUserEmail(): String? = null
        override fun fetchUserStatus(callback: (String?) -> Unit) = callback(null)
        override fun revokeCurrentSession() = Unit
        override fun handleAuthFailure() = Unit
    }
}
