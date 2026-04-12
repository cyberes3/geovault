package com.geovault.uploader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadErrorMessageFormatterTest {

    @Test
    fun `fromStatusCode maps known auth error`() {
        val text = UploadErrorMessageFormatter.fromStatusCode(401, "")
        assertEquals("Upload failed (401)\nAPI key is invalid or expired.\nCheck Settings.", text)
    }

    @Test
    fun `fromStatusCode appends trimmed server message`() {
        val serverMessage = "x".repeat(120)
        val text = UploadErrorMessageFormatter.fromStatusCode(500, serverMessage)
        assertTrue(text.startsWith("Upload failed (500)\nServer error. Try again later.\n\n"))
        assertTrue(text.endsWith("x".repeat(100)))
    }
}
