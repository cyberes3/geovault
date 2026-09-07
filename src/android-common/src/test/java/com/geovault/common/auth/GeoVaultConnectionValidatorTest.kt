package com.geovault.common.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultConnectionValidatorTest {

    @Test
    fun notConfiguredWhenUrlBlankOrSignedOut() = runBlocking {
        val validator = GeoVaultConnectionValidator(
            serverConfigService = FakeServerConfig(""),
            authSessionService = FakeAuthSession(loggedIn = false),
            fetchUserStatus = { error("should not fetch") },
        )
        assertEquals(GeoVaultConnectionStatus.NotConfigured, validator.validate())
    }

    @Test
    fun connectedWhenEmailPresent() = runBlocking {
        val validator = GeoVaultConnectionValidator(
            serverConfigService = FakeServerConfig("https://example.test"),
            authSessionService = FakeAuthSession(loggedIn = true),
            fetchUserStatus = {
                FetchUserStatusResult(email = "user@example.test", isUserStatusEndpointReachable = true)
            },
        )
        val status = validator.validate()
        assertTrue(status is GeoVaultConnectionStatus.Connected)
        assertEquals("user@example.test", (status as GeoVaultConnectionStatus.Connected).email)
    }

    @Test
    fun unauthorizedWhenReachableWithoutEmail() = runBlocking {
        val validator = GeoVaultConnectionValidator(
            serverConfigService = FakeServerConfig("https://example.test"),
            authSessionService = FakeAuthSession(loggedIn = true),
            fetchUserStatus = {
                FetchUserStatusResult(email = null, isUserStatusEndpointReachable = true)
            },
        )
        assertEquals(GeoVaultConnectionStatus.Unauthorized, validator.validate())
    }

    @Test
    fun unreachableWhenStatusProbeFails() = runBlocking {
        val validator = GeoVaultConnectionValidator(
            serverConfigService = FakeServerConfig("https://example.test"),
            authSessionService = FakeAuthSession(loggedIn = true),
            fetchUserStatus = {
                FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false)
            },
        )
        assertEquals(GeoVaultConnectionStatus.Unreachable, validator.validate())
    }

    private class FakeServerConfig(
        private val url: String,
    ) : ServerConfigService {
        override fun getServerUrl(): String = url
        override fun setServerUrl(url: String) = Unit
        override fun normalizeServerUrl(url: String): String = url
        override fun getNormalizedServerUrl(): String = url
        override fun resolveServerUrlToCanonical(url: String): Result<String> = Result.success(url)
    }

    private class FakeAuthSession(
        private val loggedIn: Boolean,
    ) : AuthSessionService {
        override fun isLoggedIn(): Boolean = loggedIn
        override fun getCachedUserEmail(): String? = null
        override fun fetchUserStatus(callback: (String?) -> Unit) = Unit
        override fun revokeCurrentSession() = Unit
        override fun handleAuthFailure() = Unit
    }
}
