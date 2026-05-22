package com.geovault.common.maps.core

import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS_DARK
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.TileClientConfig
import com.geovault.common.maps.model.TileSource
import com.geovault.common.maps.model.TileSourceResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MapMetadataTempCacheTest {

    private val appContext get() = RuntimeEnvironment.getApplication()

    @After
    fun clearCache() {
        MapMetadataTempCache.clearAll(appContext)
    }

    @Test
    fun tileSources_areStoredUnderAndroidCacheDir() {
        val serverUrl = "https://geovault.example.com"
        val response = TileSourceResponse(sources = allExpectedSources())

        MapMetadataTempCache.writeTileSources(appContext, serverUrl, response)

        val file = MapMetadataTempCache.tileSourcesFile(appContext, serverUrl)
        assertTrue(file.exists())
        assertTrue(file.canonicalPath.startsWith(appContext.cacheDir.canonicalPath))
        assertEquals(response, MapMetadataTempCache.readTileSources(appContext, serverUrl))
    }

    @Test
    fun styleJson_isStoredUnderAndroidCacheDir() {
        val styleUrl = "https://geovault.example.com/api/maps/style.json"
        val styleJson = """{"version":8,"sources":{},"layers":[]}"""

        MapMetadataTempCache.writeStyleJson(appContext, styleUrl, styleJson)

        val file = MapMetadataTempCache.styleFile(appContext, styleUrl)
        assertTrue(file.exists())
        assertTrue(file.canonicalPath.startsWith(appContext.cacheDir.canonicalPath))
        assertEquals(styleJson, MapMetadataTempCache.readStyleJson(appContext, styleUrl))
    }

    @Test
    fun transientFailure_usesCachedTileSourcesAsStaleSuccess() {
        val serverUrl = "https://geovault.example.com"
        val sources = allExpectedSources()
        MapMetadataTempCache.writeTileSources(appContext, serverUrl, TileSourceResponse(sources = sources))

        val result = TileSourceFetchResult.TransientFailure("Network unavailable.")
            .withCachedFallback(appContext, serverUrl)

        assertTrue(result is TileSourceFetchResult.Success)
        val success = result as TileSourceFetchResult.Success
        assertTrue(success.isStale)
        assertFalse(success.isCacheable())
        assertEquals(sources, success.sources)
    }

    private fun allExpectedSources(): List<TileSource> = listOf(
        source(SOURCE_MAPTILER_STREETS),
        source(SOURCE_MAPTILER_STREETS_DARK),
        source(SOURCE_MAPTILER_HYBRID),
        source(SOURCE_MAPTILER_TOPO),
    )

    private fun source(id: String) = TileSource(
        id = id,
        name = id,
        type = "maptiler",
        client_config = TileClientConfig(style_url = "/api/maps/$id/style.json"),
    )
}
