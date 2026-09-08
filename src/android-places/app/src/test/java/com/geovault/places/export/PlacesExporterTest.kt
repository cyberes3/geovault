package com.geovault.places.export

import com.geovault.places.domain.PlacesListProjection
import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.samplePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesExporterTest {
    @Test
    fun exportableListMatchesShareAndEmergencySources() {
        val live = samplePlace(name = "Keep")
        val draft = samplePlace(
            key = PlaceKey.local("n"),
            serverId = null,
            name = "Draft",
            pending = PendingChange.Create,
        )
        val deleted = samplePlace(
            key = PlaceKey.server(9),
            serverId = 9,
            name = "Gone",
            pending = PendingChange.Delete(samplePlace(name = "Gone").content),
        )
        val exportable = PlacesListProjection.exportable(listOf(live, draft, deleted))

        assertEquals(listOf("Keep", "Draft"), exportable.map { it.content.name })
        val text = PlacesExporter.buildPlainText(exportable, exportedAt = "2026-01-02 03:04")
        assertTrue(text.contains("Keep"))
        assertTrue(text.contains("Draft"))
        assertFalse(text.contains("Gone"))
        assertTrue(PlacesExporter.buildKmzBytes(exportable).isNotEmpty())
        assertTrue(PlacesExporter.export(exportable, PlacesExportFormat.PLAIN_TEXT).isNotEmpty())
    }

    @Test
    fun plainTextIncludesAddressAndCoordinates() {
        val place = samplePlace(name = "Camp", address = "Trailhead", description = "Notes")
        val block = PlacesExporter.plainTextBlock(place)

        assertTrue(block.contains("Camp"))
        assertTrue(block.contains("Trailhead"))
        assertTrue(block.contains("Notes"))
        assertTrue(block.contains("10.000000, 20.000000"))
    }
}
