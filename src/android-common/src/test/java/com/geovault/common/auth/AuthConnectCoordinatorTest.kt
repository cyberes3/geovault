package com.geovault.common.auth

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AuthConnectCoordinatorTest {

    @Test
    fun secondLaunchCancelsFirstAndOnlyLatestResultApplies() = runBlocking {
        val firstGate = CompletableDeferred<Result<String>>()
        val secondGate = CompletableDeferred<Result<String>>()
        val resolveInvocation = AtomicInteger(0)
        val firstCancelled = AtomicBoolean(false)
        val serverConfig = object : ServerConfigService {
            override fun getServerUrl(): String = ""
            override fun setServerUrl(url: String, commit: Boolean) = Unit
            override fun normalizeServerUrl(url: String): String = "http://example.test"
            override fun getNormalizedServerUrl(): String = "http://example.test"
            override fun resolveServerUrlToCanonical(
                url: String,
                callback: (Result<String>) -> Unit,
            ): () -> Unit {
                val invocation = resolveInvocation.incrementAndGet()
                val gate = if (invocation == 1) firstGate else secondGate
                val cancelFlag = if (invocation == 1) firstCancelled else AtomicBoolean(false)
                launch {
                    val result = gate.await()
                    if (!cancelFlag.get()) {
                        callback(result)
                    }
                }
                return {
                    cancelFlag.set(true)
                    gate.cancel()
                }
            }
        }
        val oauth = FakeOAuthPreparationService()
        val controller = CommonInitialAuthController(
            serverConfigService = serverConfig,
            authSessionService = FakeAuthSessionService(),
            oauthPreparationService = oauth,
            peerServerUrlsProvider = { emptySet() },
        )
        val coordinator = AuthConnectCoordinator(this, controller)
        val results = mutableListOf<CommonInitialAuthController.OAuthPreparationResult>()
        var connectingCount = 0

        coordinator.launch(
            rawServerUrl = "first",
            onConnecting = { connectingCount++ },
            onResult = { results.add(it) },
        )
        yield()
        coordinator.launch(
            rawServerUrl = "second",
            onConnecting = { connectingCount++ },
            onResult = { results.add(it) },
        )

        firstGate.complete(Result.success("https://stale.example"))
        delay(50)
        assertTrue(results.isEmpty())

        secondGate.complete(Result.success("https://fresh.example"))
        delay(100)

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
