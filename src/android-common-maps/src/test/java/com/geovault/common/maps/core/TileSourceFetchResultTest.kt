package com.geovault.common.maps.core

import com.geovault.common.maps.model.MapConfigError
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS_DARK
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.TileClientConfig
import com.geovault.common.maps.model.TileSource
import com.geovault.common.maps.model.TileSourceResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSourceFetchResultTest {

    @Test
    fun configErrorsBecomeTerminalConfigurationFailure() {
        val result = TileSourceResponse(
            sources = listOf(streetsSource()),
            map_config_errors = listOf(
                MapConfigError(
                    code = "maplibre_not_configured",
                    message = "Map setup is incomplete. Code: maplibre_not_configured.",
                ),
            ),
        ).toTileSourceFetchResult()

        assertTrue(result is TileSourceFetchResult.ConfigurationError)
        val message = (result as TileSourceFetchResult.ConfigurationError).message
        assertTrue(message.contains("Code: maplibre_not_configured"))
    }

    @Test
    fun missingExpectedMapsBecomeTerminalConfigurationFailure() {
        val result = TileSourceResponse(
            sources = listOf(streetsSource()),
        ).toTileSourceFetchResult()

        assertTrue(result is TileSourceFetchResult.ConfigurationError)
        val message = (result as TileSourceFetchResult.ConfigurationError).message
        assertTrue(message.contains("Code: required_maplibre_basemaps_missing"))
        assertTrue(message.contains(SOURCE_MAPTILER_STREETS_DARK))
        assertTrue(message.contains(SOURCE_MAPTILER_HYBRID))
        assertTrue(message.contains(SOURCE_MAPTILER_TOPO))
    }

    @Test
    fun healthyResponseKeepsSourcesRenderable() {
        val sources = listOf(
            streetsSource(),
            maptilerSource(SOURCE_MAPTILER_STREETS_DARK, "Dark Streets"),
            maptilerSource(SOURCE_MAPTILER_HYBRID, "Satellite Hybrid"),
            maptilerSource(SOURCE_MAPTILER_TOPO, "Topographic"),
        )

        val result = TileSourceResponse(sources = sources).toTileSourceFetchResult()

        assertTrue(result is TileSourceFetchResult.Success)
        assertEquals(sources, (result as TileSourceFetchResult.Success).sources)
    }

    @Test
    fun onlySuccessfulSourceFetchesAreCacheable() {
        assertTrue(TileSourceFetchResult.Success(listOf(streetsSource())).isCacheable())
        assertFalse(TileSourceFetchResult.Success(listOf(streetsSource()), isStale = true).isCacheable())
        assertTrue(TileSourceFetchResult.Success(listOf(streetsSource()), forcedCacheOnly = true).isCacheable())
        assertFalse(TileSourceFetchResult.ConfigurationError("Map setup is incomplete.").isCacheable())
        assertFalse(TileSourceFetchResult.TransientFailure("Network unavailable.").isCacheable())
    }

    private fun streetsSource() = TileSource(
        id = SOURCE_MAPTILER_STREETS,
        name = "Streets",
        type = "maptiler",
        client_config = TileClientConfig(style_url = "/api/maps/maptiler/streets/style.json"),
    )

    private fun maptilerSource(id: String, name: String) = TileSource(
        id = id,
        name = name,
        type = "maptiler",
        client_config = TileClientConfig(style_url = "/api/maps/$id/style.json"),
    )
}
