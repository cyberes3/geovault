package com.geovault.tracker.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultPointRecordingLogFilenameTest {

    @Test
    fun buildExportDisplayName_containsPointRecordingMarker() {
        val name = GeoVaultPointRecordingLogFilename.buildExportDisplayName(
            "MyApp",
            java.time.Instant.parse("2026-05-14T12:30:45Z"),
            compressed = true,
        )

        assertTrue(name.contains("point-recording"))
        assertTrue(name.endsWith(".txt.gz"))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertTrue(name.startsWith("MyApp_"))
    }
}
