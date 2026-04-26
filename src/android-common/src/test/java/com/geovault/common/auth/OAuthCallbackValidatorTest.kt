package com.geovault.common.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OAuthCallbackValidatorTest {

    @Test
    fun `validate returns error when code missing`() {
        val result = OAuthCallbackValidator.validate(
            OAuthCallbackValidationInput(
                code = null,
                state = "abc",
                oauthError = "access_denied",
                pkceState = "verifier" to "abc",
                serverUrl = "https://example.test",
            )
        )

        assertTrue(result is OAuthCallbackValidationResult.Error)
        assertEquals("access_denied", (result as OAuthCallbackValidationResult.Error).message)
    }

    @Test
    fun `validate returns error on mismatched state`() {
        val result = OAuthCallbackValidator.validate(
            OAuthCallbackValidationInput(
                code = "auth_code",
                state = "wrong",
                oauthError = null,
                pkceState = "verifier" to "expected",
                serverUrl = "https://example.test",
            )
        )

        assertTrue(result is OAuthCallbackValidationResult.Error)
        assertEquals("Invalid state", (result as OAuthCallbackValidationResult.Error).message)
    }

    @Test
    fun `validate returns ready on valid payload`() {
        val result = OAuthCallbackValidator.validate(
            OAuthCallbackValidationInput(
                code = "auth_code",
                state = "expected",
                oauthError = null,
                pkceState = "verifier" to "expected",
                serverUrl = "https://example.test",
            )
        )

        assertTrue(result is OAuthCallbackValidationResult.Ready)
        val ready = result as OAuthCallbackValidationResult.Ready
        assertEquals("auth_code", ready.code)
        assertEquals("verifier", ready.codeVerifier)
        assertEquals("https://example.test", ready.serverUrl)
    }
}
