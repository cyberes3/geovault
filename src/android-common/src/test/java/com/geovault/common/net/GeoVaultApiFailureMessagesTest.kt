package com.geovault.common.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultApiFailureMessagesTest {
    @Test
    fun format_missingServerUrl() {
        val message = GeoVaultApiFailureMessages.format(
            GeoVaultApiFailure(httpCode = null, serverMessage = "Missing server URL"),
        )
        assertEquals("Server URL is not configured.", message)
    }

    @Test
    fun format_authAndEnvelopeClient() {
        assertEquals(
            "Session expired. Sign in again.",
            GeoVaultApiFailureMessages.format(
                GeoVaultApiFailure(httpCode = 401, serverMessage = "Unauthorized"),
            ),
        )
        assertEquals(
            "Name is required",
            GeoVaultApiFailureMessages.format(
                GeoVaultApiFailure(httpCode = 400, serverMessage = "Name is required"),
            ),
        )
        val server = GeoVaultApiFailureMessages.format(
            GeoVaultApiFailure(httpCode = 503, serverMessage = null),
        )
        assertTrue(server.contains("503"))
    }
}
