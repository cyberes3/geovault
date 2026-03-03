package com.geovault.places

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import android.content.Context
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import com.geovault.common.GeovaultAuthManager

@Parcelize
data class FeatureCollection(
    val type: String,
    val features: List<Feature>
) : Parcelable

@Parcelize
data class Feature(
    val type: String,
    val geometry: Geometry,
    val properties: Properties
) : Parcelable

@Parcelize
data class Geometry(
    val type: String,
    val coordinates: List<Double>
) : Parcelable

@Parcelize
data class Properties(
    val database_id: Int? = null,
    val name: String?,
    val description: String?,
    val created_at: String?,
    val address: String? = null
) : Parcelable

/** Backend returns { "data": { "query": "...", "features": [ { "coordinates": [lng, lat], "place_name": "...", "text": "..."? }, ... ] } } */
data class AddressSearchResponse(val data: GeocodingResponseData?)

data class GeocodingResponseData(
    val query: String? = null,
    val features: List<AddressSearchResult>? = null
)

data class AddressSearchResult(
    val coordinates: List<Double>?,
    val place_name: String?,
    val text: String?
)

/** Tile source configuration from /api/tiles/sources/ */
data class TileSourceResponse(val sources: List<TileSource>)

data class TileSource(
    val id: String,
    val name: String,
    val type: String,
    val requires_proxy: Boolean = false,
    val needs_hillshade: Boolean = false,
    val hidden: Boolean = false,
    val client_config: TileClientConfig
)


data class TileClientConfig(
    val type: String? = null,
    val url: String? = null,
    val tileSize: Int? = null,
    val attribution: String? = null
)

/** Utility to manage map sources, toggling, and persistence */
class MapSourceManager(private val context: android.content.Context) {
    private val prefs = context.getSharedPreferences("geovault_prefs", android.content.Context.MODE_PRIVATE)
    private var availableSources: List<TileSource> = listOf(
        TileSource("osm", "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")),
        TileSource("google-satellite-hybrid", "Google Satellite Hybrid", "xyz", client_config = TileClientConfig(url = "/api/tiles/google-satellite-hybrid/{z}/{x}/{y}"))
    )

    fun setSources(sources: List<TileSource>) {
        // Filter for exactly the two sources requested
        val allowedIds = setOf("osm", "google-satellite-hybrid")
        val filtered = sources.filter { it.id in allowedIds }
        
        // Ensure we always have both even if server list is incomplete or fetch fails
        val baseSources = mutableListOf<TileSource>()
        
        // OSM (Mapnik)
        if (filtered.none { it.id == "osm" }) {
            baseSources.add(TileSource("osm", "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")))
        } else {
            baseSources.add(filtered.find { it.id == "osm" }!!)
        }
        
        // Google Hybrid
        if (filtered.none { it.id == "google-satellite-hybrid" }) {
            baseSources.add(TileSource("google-satellite-hybrid", "Google Satellite Hybrid", "xyz", client_config = TileClientConfig(url = "/api/tiles/google-satellite-hybrid/{z}/{x}/{y}")))
        } else {
            baseSources.add(filtered.find { it.id == "google-satellite-hybrid" }!!)
        }
        
        availableSources = baseSources
    }

    fun getSelectedSourceId(): String {
        return prefs.getString("selected_map_source", "osm") ?: "osm"
    }

    fun setSelectedSourceId(id: String) {
        prefs.edit().putString("selected_map_source", id).apply()
    }

    fun getNextSourceId(): String {
        if (availableSources.isEmpty()) return "osm"
        val currentId = getSelectedSourceId()
        val index = availableSources.indexOfFirst { it.id == currentId }
        val nextIndex = (index + 1) % availableSources.size
        return availableSources[nextIndex].id
    }

    fun getTileSource(id: String): org.osmdroid.tileprovider.tilesource.ITileSource {
        val source = availableSources.find { it.id == id }
        if (source == null || id == "osm") {
            return org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
        }

        val config = source.client_config
        val url = config.url ?: return org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
        
        // Convert /api/tiles/source/{z}/{x}/{y} to the format OSMDroid expects: .../{z}/{x}/{y}.png
        val baseUrl = com.geovault.common.GeovaultAuthManager.getServerUrl(context).let {
            if (it.isEmpty()) return org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
            if (it.endsWith("/")) it.substring(0, it.length - 1) else it
        }
        
        // Ensure URL starts with the server base if it's a relative path
        val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
        
        // OSMDroid XYTileSource expects the URL up to the {z} part
        val pattern = "/{z}/{x}/{y}"
        val finalUrl = if (fullUrl.contains(pattern)) {
            fullUrl.substring(0, fullUrl.indexOf(pattern) + 1)
        } else {
            fullUrl
        }

        return org.osmdroid.tileprovider.tilesource.XYTileSource(
            source.name,
            0, 20, // zoom levels
            config.tileSize ?: 256,
            "", // extension (handled by proxy or not needed if full path in template)
            arrayOf(finalUrl),
            config.attribution
        )
    }

    /** Helper to fetch sources from server and update state */
    fun fetchSources(api: GeovaultApi, onComplete: () -> Unit) {
        api.getTileSources().enqueue(object : retrofit2.Callback<TileSourceResponse> {
            override fun onResponse(call: retrofit2.Call<TileSourceResponse>, response: retrofit2.Response<TileSourceResponse>) {
                val sources = response.body()?.sources
                if (sources != null) {
                    setSources(sources)
                    onComplete()
                }
            }
            override fun onFailure(call: retrofit2.Call<TileSourceResponse>, t: Throwable) {
                // Fallback to minimal sources if fetch fails
                setSources(listOf(
                    TileSource("osm", "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")),
                    TileSource("google-satellite-hybrid", "Google Satellite Hybrid", "xyz", client_config = TileClientConfig(url = "/api/tiles/google-satellite-hybrid/{z}/{x}/{y}"))
                ))
                onComplete()
            }
        })
    }
}
