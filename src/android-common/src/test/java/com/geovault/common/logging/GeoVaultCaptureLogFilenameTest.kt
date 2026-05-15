package com.geovault.common.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultCaptureLogFilenameTest {

    @Test
    fun sanitize_replacesInvalidCharacters() {
        assertEquals("a_b_c_d", GeoVaultCaptureLogFilename.sanitizeForFilename("a/b:c*d?"))
    }

    @Test
    fun sanitize_emptyBecomesApp() {
        assertEquals("app", GeoVaultCaptureLogFilename.sanitizeForFilename("   "))
    }

    @Test
    fun buildExportDisplayName_containsNoPathSeparators() {
        val name = GeoVaultCaptureLogFilename.buildExportDisplayName("MyApp", java.time.Instant.parse("2026-05-14T12:30:45Z"))
        assertTrue(name.endsWith(".txt"))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertTrue(name.startsWith("MyApp_"))
    }
}
