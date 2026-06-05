package com.geovault.common.auth

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GeoVaultAccountControllerTest {

    @Test
    fun readyKeepsConnectingAndSetsOauthUrl() = runBlocking {
        val services = FakeAuthServices()
        val controller = newController(services)

        controller.onServerUrlChanged("example.com")
        controller.connect()
        delay(10)

        assertTrue(controller.state.value.isConnecting)
        assertEquals("https://example.com/oauth", controller.state.value.oauthUrl)
        assertNull(controller.state.value.infoMessage)
    }

    @Test
    fun oauthConsumedClearsOnlyOauthUrl() = runBlocking {
        val services = FakeAuthServices()
        val controller = newController(services)

        controller.onServerUrlChanged("example.com")
        controller.connect()
        delay(10)
        controller.onOauthUrlConsumed()

        assertTrue(controller.state.value.isConnecting)
        assertNull(controller.state.value.oauthUrl)
    }

    @Test
    fun hostResumedClearsConnectingWhenStillSignedOut() = runBlocking {
        val services = FakeAuthServices()
        val controller = newController(services)

        controller.onServerUrlChanged("example.com")
        controller.connect()
        delay(10)
        controller.onHostResumed()

        assertFalse(controller.state.value.isLoggedIn)
        assertFalse(controller.state.value.isConnecting)
        assertNull(controller.state.value.oauthUrl)
    }

    @Test
    fun invalidServerUrlClearsConnectingAndSetsMessage() = runBlocking {
        val services = FakeAuthServices(normalizedUrl = "")
        val controller = newController(services)

        controller.onServerUrlChanged("   ")
        controller.connect()
        delay(10)

        assertFalse(controller.state.value.isConnecting)
        assertEquals("Server URL is required.", controller.state.value.infoMessage)
    }

    @Test
    fun errorClearsConnectingAndSetsMessage() = runBlocking {
        val services = FakeAuthServices(
            resolveResult = Result.failure(IllegalStateException("no route"))
        )
        val controller = newController(services)

        controller.onServerUrlChanged("bad.example")
        controller.connect()
        delay(10)

        assertFalse(controller.state.value.isConnecting)
        assertEquals("Could not reach server.", controller.state.value.infoMessage)
    }

    private fun CoroutineScope.newController(services: FakeAuthServices): GeoVaultAccountController {
        return GeoVaultAccountController(
            scope = this,
            appContext = ApplicationProvider.getApplicationContext(),
            authController = CommonInitialAuthController(
                serverConfigService = services,
                authSessionService = services,
                oauthPreparationService = services,
                peerServerUrlsProvider = { emptySet() },
            )
        )
    }

}

    private class FakeAuthServices(
        private val normalizedUrl: String = "https://example.com",
        private val resolveResult: Result<String> = Result.success("https://example.com"),
    ) : ServerConfigService, AuthSessionService, OAuthPreparationService {
    private var serverUrl: String = ""

    override fun getServerUrl(): String = serverUrl
    override fun setServerUrl(url: String, commit: Boolean) {
        serverUrl = url
    }
        override fun normalizeServerUrl(url: String): String = normalizedUrl
    override fun getNormalizedServerUrl(): String = normalizeServerUrl(serverUrl)
    override fun resolveServerUrlToCanonical(url: String, callback: (Result<String>) -> Unit): () -> Unit {
        callback(resolveResult)
        return {}
    }

    override fun isLoggedIn(): Boolean = false
    override fun getCachedUserEmail(): String? = null
    override fun fetchUserStatus(callback: (String?) -> Unit) = callback(null)
    override fun revokeCurrentSession() = Unit
    override fun handleAuthFailure() = Unit

    override fun generatePkcePair(): Pair<String, String> = "verifier" to "challenge"
    override fun generateOAuthStateNonce(length: Int): String = "state"
    override fun savePkceState(verifier: String, state: String) = Unit
    override fun buildAuthorizeUrl(serverUrl: String, codeChallenge: String, state: String): String {
        return "$serverUrl/oauth"
    }

    private fun String.prependHttpsIfMissing(): String {
        if (isBlank() || startsWith("http://") || startsWith("https://")) return this
        return "https://$this"
    }
}
