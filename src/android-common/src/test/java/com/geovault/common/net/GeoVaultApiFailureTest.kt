package com.geovault.common.net

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultApiFailureTest {
    @Test
    fun userMessage_usesServerPrefixWhenHttpCodePresent() {
        val failure = GeoVaultApiFailure(httpCode = 500, serverMessage = "boom")
        assertEquals("Server Error: boom", failure.userMessage())
    }

    @Test
    fun userMessage_fallsBackToHttpCodeWhenServerMessageBlank() {
        val failure = GeoVaultApiFailure(httpCode = 404, serverMessage = "  ")
        assertEquals("Server Error: HTTP 404", failure.userMessage())
    }

    @Test
    fun userMessage_usesNetworkPrefixWhenHttpCodeMissing() {
        val failure = GeoVaultApiFailure(httpCode = null, serverMessage = "timeout")
        assertEquals("Network failed: timeout", failure.userMessage())
    }
}
