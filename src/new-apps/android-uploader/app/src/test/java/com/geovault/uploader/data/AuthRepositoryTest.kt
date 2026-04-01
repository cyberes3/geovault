package com.geovault.uploader.data

import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.OAuthPreparationService
import com.geovault.common.auth.ServerConfigService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {

    @Test
    fun `prepareOAuthConnection returns InvalidServerUrl when normalized URL is blank`() {
        val serverConfig = FakeServerConfigService(normalizedUrl = "")
        val repository = AuthRepository(
            serverConfigService = serverConfig,
            authSessionService = FakeAuthSessionService(),
            oauthPreparationService = FakeOAuthPreparationService(),
            peerServerUrlsProvider = { emptySet() }
        )

        val result = runSuspend { repository.prepareOAuthConnection("   ") }

        assertTrue(result is AuthRepository.OAuthPreparationResult.InvalidServerUrl)
    }

    @Test
    fun `prepareOAuthConnection persists canonical URL and returns authorize URL`() {
        val serverConfig = FakeServerConfigService(
            normalizedUrl = "http://example.test",
            canonicalResult = Result.success("http://canonical.example")
        )
        val oauth = FakeOAuthPreparationService()
        val repository = AuthRepository(
            serverConfigService = serverConfig,
            authSessionService = FakeAuthSessionService(),
            oauthPreparationService = oauth,
            peerServerUrlsProvider = { emptySet() }
        )

        val result = runSuspend { repository.prepareOAuthConnection("example.test") }

        assertTrue(result is AuthRepository.OAuthPreparationResult.Ready)
        val ready = result as AuthRepository.OAuthPreparationResult.Ready
        assertEquals("http://canonical.example/oauth?state=${oauth.savedState}", ready.oauthUrl)
        assertEquals("http://canonical.example", serverConfig.savedUrl)
        assertEquals("verifier", oauth.savedVerifier)
        assertEquals(16, oauth.savedState.length)
    }

    @Test
    fun `prepareOAuthConnection returns UnreachableServer on canonical resolution failure`() {
        val serverConfig = FakeServerConfigService(
            normalizedUrl = "http://example.test",
            canonicalResult = Result.failure(IllegalStateException("offline"))
        )
        val repository = AuthRepository(
            serverConfigService = serverConfig,
            authSessionService = FakeAuthSessionService(),
            oauthPreparationService = FakeOAuthPreparationService(),
            peerServerUrlsProvider = { emptySet() }
        )

        val result = runSuspend { repository.prepareOAuthConnection("example.test") }

        assertTrue(result is AuthRepository.OAuthPreparationResult.UnreachableServer)
    }

    private class FakeServerConfigService(
        private val normalizedUrl: String,
        private val canonicalResult: Result<String> = Result.success("http://example.test")
    ) : ServerConfigService {
        var savedUrl: String = ""

        override fun getServerUrl(): String = savedUrl

        override fun setServerUrl(url: String, commit: Boolean) {
            savedUrl = url
        }

        override fun normalizeServerUrl(url: String): String = normalizedUrl

        override fun getNormalizedServerUrl(): String = normalizedUrl

        override fun resolveServerUrlToCanonical(url: String, callback: (Result<String>) -> Unit) {
            callback(canonicalResult)
        }
    }

    private class FakeOAuthPreparationService : OAuthPreparationService {
        var savedVerifier: String = ""
        var savedState: String = ""

        override fun generatePkcePair(): Pair<String, String> = "verifier" to "challenge"

        override fun savePkceState(verifier: String, state: String) {
            savedVerifier = verifier
            savedState = state
        }

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

private fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
