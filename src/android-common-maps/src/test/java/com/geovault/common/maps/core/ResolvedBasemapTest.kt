package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedBasemapTest {

    @Test
    fun raster_factory_acceptsXyzTemplate() {
        val basemap = ResolvedBasemap.raster("osm", "https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        assertNotNull(basemap)
        assertEquals("osm", basemap!!.sourceId)
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", basemap.tileTemplate)
        assertTrue(basemap.cacheKey.startsWith(ResolvedBasemap.RASTER_KEY_PREFIX))
    }

    @Test
    fun raster_factory_rejectsBlank() {
        assertNull(ResolvedBasemap.raster("osm", null))
        assertNull(ResolvedBasemap.raster("osm", ""))
        assertNull(ResolvedBasemap.raster("osm", "   "))
    }

    @Test
    fun raster_factory_rejectsBlankSourceId() {
        assertNull(ResolvedBasemap.raster("", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertNull(ResolvedBasemap.raster("   ", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
    }

    @Test
    fun raster_factory_trimsWhitespace() {
        val basemap = ResolvedBasemap.raster("osm", "  https://example.com/{z}/{x}/{y}.png  ")
        assertNotNull(basemap)
        assertEquals("https://example.com/{z}/{x}/{y}.png", basemap!!.tileTemplate)
    }

    @Test
    fun vector_factory_acceptsHttpsUrl() {
        val basemap = ResolvedBasemap.vector("maptiler-streets", "https://api.maptiler.com/maps/streets/style.json")
        assertNotNull(basemap)
        assertEquals("maptiler-streets", basemap!!.sourceId)
        assertEquals("https://api.maptiler.com/maps/streets/style.json", basemap.styleUrl.toString())
        assertTrue(basemap.cacheKey.startsWith(ResolvedBasemap.VECTOR_KEY_PREFIX))
    }

    @Test
    fun vector_factory_rejectsBlank() {
        assertNull(ResolvedBasemap.vector("maptiler", null))
        assertNull(ResolvedBasemap.vector("maptiler", ""))
        assertNull(ResolvedBasemap.vector("maptiler", "   "))
    }

    @Test
    fun vector_factory_rejectsUnparseableUrl() {
        // No scheme, plain path — not a parseable absolute URL.
        assertNull(ResolvedBasemap.vector("maptiler", "/api/tiles/style.json"))
        assertNull(ResolvedBasemap.vector("maptiler", "not a url"))
    }

    @Test
    fun vector_factory_rejectsBlankSourceId() {
        assertNull(ResolvedBasemap.vector("", "https://example.com/style.json"))
    }

    @Test
    fun cacheKey_isStableAndSourceTypeDistinguishing() {
        val raster = ResolvedBasemap.raster("osm", "https://t/{z}/{x}/{y}.png")!!
        val vector = ResolvedBasemap.vector("osm", "https://t/style.json")!!
        // Same sourceId but different basemap kinds must produce different cache keys.
        assertTrue(raster.cacheKey != vector.cacheKey)
        assertTrue(raster.cacheKey.startsWith("raster:"))
        assertTrue(vector.cacheKey.startsWith("vector:"))
    }
}
