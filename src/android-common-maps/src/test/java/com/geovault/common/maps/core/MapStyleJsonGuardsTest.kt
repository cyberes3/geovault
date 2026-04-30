package com.geovault.common.maps.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MapStyleJsonGuardsTest {

    @Test
    fun normalStyleJson_ok() {
        val json = """
            {
              "version": 8,
              "glyphs": "https://example.com/fonts/{fontstack}/{range}.pbf",
              "sprite": "https://example.com/sprite",
              "sources": {
                "osm": { "type": "raster", "tiles": ["https://a.tile/{z}/{x}/{y}.png"], "tileSize": 256 }
              },
              "layers": []
            }
        """.trimIndent()
        assertFalse(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun emptyGlyphs_rejected() {
        val json = """
            {
              "version": 8,
              "glyphs": "",
              "sources": {},
              "layers": []
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun whitespaceOnlyGlyphs_rejected() {
        val json = """
            {
              "version": 8,
              "glyphs": "   ",
              "sources": {},
              "layers": []
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun malformedGlyphsUrl_rejected() {
        // Relative path, no scheme and no leading slash — MapLibre's HttpUrl.parse rejects this.
        val json = """
            {
              "version": 8,
              "glyphs": "fonts/{fontstack}/{range}.pbf",
              "sources": {},
              "layers": []
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun serverRelativeGlyphs_ok() {
        val json = """
            {
              "version": 8,
              "glyphs": "/api/fonts/{fontstack}/{range}.pbf",
              "sources": {},
              "layers": [
                { "id": "labels", "type": "symbol", "layout": { "text-field": ["get", "name"] } }
              ]
            }
        """.trimIndent()
        assertFalse(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun missingGlyphsWithTextLayer_rejected() {
        val json = """
            {
              "version": 8,
              "sources": {},
              "layers": [
                { "id": "labels", "type": "symbol", "layout": { "text-field": "{name}" } }
              ]
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun missingGlyphsWithoutTextLayer_ok() {
        val json = """
            {
              "version": 8,
              "sources": {},
              "layers": [
                { "id": "icons", "type": "symbol", "layout": { "icon-image": "marker" } }
              ]
            }
        """.trimIndent()
        assertFalse(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun serverStyleNormalizer_addsServerRelativeGlyphsForTextLayers() {
        val normalized = MapStyleJsonNormalizer.normalizeServerStyle(
            """
                {
                  "version": 8,
                  "sources": {},
                  "layers": [
                    { "id": "labels", "type": "symbol", "layout": { "text-field": "{name}" } }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("/api/fonts/{fontstack}/{range}.pbf", JSONObject(normalized).getString("glyphs"))
        assertFalse(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(normalized))
    }

    @Test
    fun emptySourceUrl_rejected() {
        val json = """
            {
              "version": 8,
              "sources": { "x": { "type": "raster", "url": "" } },
              "layers": []
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun emptyTileInArray_rejected() {
        val json = """
            {
              "version": 8,
              "sources": {
                "x": { "type": "raster", "tiles": ["https://a/{z}/{x}/{y}.png", ""] }
              },
              "layers": []
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun spriteArrayForm_ok() {
        val json = """
            {
              "version": 8,
              "sprite": [
                { "id": "default", "url": "https://example.com/sprite" },
                { "id": "extra", "url": "https://example.com/extra" }
              ],
              "sources": {},
              "layers": []
            }
        """.trimIndent()
        assertFalse(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun spriteArrayWithEmptyUrl_rejected() {
        val json = """
            {
              "version": 8,
              "sprite": [
                { "id": "default", "url": "https://example.com/sprite" },
                { "id": "extra", "url": "" }
              ],
              "sources": {},
              "layers": []
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun spriteArrayWithMalformedUrl_rejected() {
        val json = """
            {
              "version": 8,
              "sprite": [
                { "id": "default", "url": "not a url at all" }
              ],
              "sources": {},
              "layers": []
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun geoJsonInlineDataObject_passesThrough() {
        // `data` is an inline GeoJSON object, not a URL — must not be flagged.
        val json = """
            {
              "version": 8,
              "sources": {
                "points": {
                  "type": "geojson",
                  "data": { "type": "FeatureCollection", "features": [] }
                }
              },
              "layers": []
            }
        """.trimIndent()
        assertFalse(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun geoJsonEmptyDataString_rejected() {
        val json = """
            {
              "version": 8,
              "sources": {
                "points": { "type": "geojson", "data": "" }
              },
              "layers": []
            }
        """.trimIndent()
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(json))
    }

    @Test
    fun malformedJson_rejected() {
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl("{ not json"))
    }

    @Test
    fun blankString_rejected() {
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl(""))
        assertTrue(MapStyleJsonGuards.hasEmptyOrUnparseableResourceUrl("   "))
    }
}
