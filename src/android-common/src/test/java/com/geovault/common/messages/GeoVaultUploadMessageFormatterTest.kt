package com.geovault.common.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultUploadMessageFormatterTest {

    @Test
    fun `fromStatusCode maps known auth error`() {
        val text = GeoVaultUploadMessageFormatter.fromStatusCode(401, "")
        assertEquals(
            "Upload failed (401)\nSession is invalid or expired.\nReconnect in Settings.",
            text,
        )
    }

    @Test
    fun `fromStatusCode appends trimmed server message`() {
        val serverMessage = "x".repeat(120)
        val text = GeoVaultUploadMessageFormatter.fromStatusCode(500, serverMessage)
        assertTrue(text.startsWith("Upload failed (500)\nServer error. Try again later.\n\n"))
        assertTrue(text.endsWith("x".repeat(100)))
    }
}
