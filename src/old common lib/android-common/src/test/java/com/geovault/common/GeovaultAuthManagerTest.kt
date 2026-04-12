package com.geovault.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeovaultAuthManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun generatePkcePair_returnsVerifierLength64() {
        val (verifier, _) = GeovaultAuthManager.generatePkcePair()
        assertEquals(64, verifier.length)
    }

    @Test
    fun generatePkcePair_verifierUsesAllowedCharacters() {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()
        val (verifier, _) = GeovaultAuthManager.generatePkcePair()
        assertTrue(verifier.all { it in allowed })
    }

    @Test
    fun generatePkcePair_challengeIsBase64UrlFormat() {
        val (_, challenge) = GeovaultAuthManager.generatePkcePair()
        assertTrue(challenge.none { it == '+' || it == '/' || it == '=' })
        assertTrue(challenge.length in 40..50)
    }

    @Test
    fun generatePkcePair_differentEachTime() {
        val (v1, c1) = GeovaultAuthManager.generatePkcePair()
        val (v2, c2) = GeovaultAuthManager.generatePkcePair()
        assertTrue(v1 != v2 || c1 != c2)
    }

    @Test
    fun buildAuthorizeUrl_containsRedirectUriAndParams() {
        GeovaultAuthManager.init(context, "https://app.example/oauth/callback", GeovaultAuthManager.OAUTH_CLIENT_ID_PLACES)
        val url = GeovaultAuthManager.buildAuthorizeUrl(
            "https://server.example",
            "challenge123",
            "state456"
        )
        assertTrue(url.startsWith("https://server.example/api/oauth/authorize/?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=geovault-android-places"))
        assertTrue(url.contains("redirect_uri="))
        assertTrue(url.contains("code_challenge=challenge123"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state456"))
        assertTrue(url.contains("scope=api"))
    }

    @Test
    fun handleAuthFailure_notifiesListener() {
        var notified = false
        val listener = object : GeovaultAuthManager.AuthFailureListener {
            override fun onAuthFailure(context: Context) {
                notified = true
            }
        }
        GeovaultAuthManager.setAuthFailureListener(listener)
        GeovaultAuthManager.handleAuthFailure(context)
        assertTrue("Listener should have been notified", notified)
    }
}
