package com.geovault.common.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonInitialAuthControllerTest {

    @Test
    fun `prepareOAuthConnection returns InvalidServerUrl for blank normalized URL`() {
        val serverConfig = FakeServerConfigService(normalizedUrl = "")
        val controller = newController(serverConfig = serverConfig)

        val result = runSuspend { controller.prepareOAuthConnection("   ") }

        assertTrue(result is CommonInitialAuthController.OAuthPreparationResult.InvalidServerUrl)
    }

    @Test
    fun `prepareOAuthConnection saves canonical URL and returns oauth URL`() {
        val serverConfig = FakeServerConfigService(
            normalizedUrl = "http://example.test",
            canonicalResult = Result.success("https://canonical.example")
        )
        val oauth = FakeOAuthPreparationService()
        val controller = newController(
            serverConfig = serverConfig,
            oauth = oauth,
        )

        val result = runSuspend { controller.prepareOAuthConnection("example.test") }

        assertTrue(result is CommonInitialAuthController.OAuthPreparationResult.Ready)
        val ready = result as CommonInitialAuthController.OAuthPreparationResult.Ready
        assertEquals("https://canonical.example/oauth?state=bbbbbbbbbbbbbbbb", ready.oauthUrl)
        assertEquals("https://canonical.example", serverConfig.savedUrl)
        assertEquals("verifier", oauth.savedVerifier)
        assertEquals("bbbbbbbbbbbbbbbb", oauth.savedState)
    }

    @Test
    fun `configured server falls back to single peer URL`() {
        val serverConfig = FakeServerConfigService(normalizedUrl = "https://kept.example")
        val controller = newController(
            serverConfig = serverConfig,
            peers = { setOf("https://peer.example") },
        )

        assertEquals("https://peer.example", controller.getConfiguredServerUrlOrPeerDefault())
    }

    @Test
    fun `configured server wins over peer fallback`() {
        val serverConfig = FakeServerConfigService(normalizedUrl = "https://kept.example").also {
            it.savedUrl = "https://configured.example"
        }
        val controller = newController(
            serverConfig = serverConfig,
            peers = { setOf("https://peer.example") },
        )

        assertEquals("https://configured.example", controller.getConfiguredServerUrlOrPeerDefault())
    }

    private fun newController(
        serverConfig: FakeServerConfigService,
        authSession: FakeAuthSessionService = FakeAuthSessionService(),
        oauth: FakeOAuthPreparationService = FakeOAuthPreparationService(),
        peers: () -> Set<String> = { emptySet() },
    ): CommonInitialAuthController {
        return CommonInitialAuthController(
            serverConfigService = serverConfig,
            authSessionService = authSession,
            oauthPreparationService = oauth,
            peerServerUrlsProvider = peers,
        )
    }

    private class FakeServerConfigService(
        private val normalizedUrl: String,
        private val canonicalResult: Result<String> = Result.success("https://example.test")
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

        override fun generateOAuthStateNonce(length: Int): String = "b".repeat(length)

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
