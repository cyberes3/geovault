package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackerMapRenderMetadataFingerprintTest {

    private fun t(
        id: String,
        name: String = id,
        color: String? = null,
        hidden: Boolean? = null,
    ): Tracker {
        val settings: Map<String, Any?>? = if (hidden != null) mapOf("hidden" to hidden) else null
        return Tracker(id = id, name = name, color = color, settings = settings)
    }

    private fun g(id: String, members: List<String>) =
        Group(id = id, name = id, track_ids = members)

    @Test
    fun `renaming a tracker changes cosmetic but not structural`() {
        val before = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a", name = "Alpha")),
            emptyList(),
            null,
        )
        val after = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a", name = "Beta")),
            emptyList(),
            null,
        )
        assertNotEquals(before.cosmetic, after.cosmetic)
        assertEquals(before.structural, after.structural)
    }

    @Test
    fun `changing color changes cosmetic but not structural`() {
        val before = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a", color = "#000000")),
            emptyList(),
            null,
        )
        val after = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a", color = "#ffffff")),
            emptyList(),
            null,
        )
        assertNotEquals(before.cosmetic, after.cosmetic)
        assertEquals(before.structural, after.structural)
    }

    @Test
    fun `flipping hidden settings flag changes structural but not cosmetic`() {
        val before = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a", hidden = false)),
            emptyList(),
            null,
        )
        val after = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a", hidden = true)),
            emptyList(),
            null,
        )
        assertEquals(before.cosmetic, after.cosmetic)
        assertNotEquals(before.structural, after.structural)
    }

    @Test
    fun `adding a tracker changes structural`() {
        val before = TrackerMapRenderMetadataFingerprint.from(listOf(t("a")), emptyList(), null)
        val after = TrackerMapRenderMetadataFingerprint.from(listOf(t("a"), t("b")), emptyList(), null)
        assertNotEquals(before.structural, after.structural)
    }

    @Test
    fun `removing a tracker changes structural`() {
        val before = TrackerMapRenderMetadataFingerprint.from(listOf(t("a"), t("b")), emptyList(), null)
        val after = TrackerMapRenderMetadataFingerprint.from(listOf(t("a")), emptyList(), null)
        assertNotEquals(before.structural, after.structural)
    }

    @Test
    fun `changing group membership changes structural`() {
        val before = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a"), t("b")),
            listOf(g("g1", listOf("a"))),
            null,
        )
        val after = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a"), t("b")),
            listOf(g("g1", listOf("a", "b"))),
            null,
        )
        assertNotEquals(before.structural, after.structural)
    }

    @Test
    fun `changing map visibility hidden ids changes structural`() {
        val before = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a"), t("b")),
            emptyList(),
            MapVisibilityResponse(),
        )
        val after = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a"), t("b")),
            emptyList(),
            MapVisibilityResponse(hidden_track_ids = listOf("b")),
        )
        assertNotEquals(before.structural, after.structural)
    }

    @Test
    fun `output is deterministic regardless of input order`() {
        val a = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("b"), t("a")),
            listOf(g("g2", listOf("b", "a")), g("g1", listOf("a"))),
            MapVisibilityResponse(hidden_track_ids = listOf("b", "a")),
        )
        val b = TrackerMapRenderMetadataFingerprint.from(
            listOf(t("a"), t("b")),
            listOf(g("g1", listOf("a")), g("g2", listOf("a", "b"))),
            MapVisibilityResponse(hidden_track_ids = listOf("a", "b")),
        )
        assertEquals(a, b)
    }

    @Test
    fun `recent_data_window changes neither cosmetic nor structural`() {
        // Filter changes are routed through TrackerMapFilterChangeReactor instead.
        val before = TrackerMapRenderMetadataFingerprint.from(
            listOf(Tracker(id = "a", name = "a", color = null, settings = mapOf("recent_data_window" to "1h"))),
            emptyList(),
            null,
        )
        val after = TrackerMapRenderMetadataFingerprint.from(
            listOf(Tracker(id = "a", name = "a", color = null, settings = mapOf("recent_data_window" to "session"))),
            emptyList(),
            null,
        )
        assertEquals(before, after)
    }
}
