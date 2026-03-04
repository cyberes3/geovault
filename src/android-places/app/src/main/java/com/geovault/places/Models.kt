package com.geovault.places

import android.content.res.Configuration
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import android.content.Context
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
    val style_url: String? = null,
    val tileSize: Int? = null,
    val attribution: String? = null
)

/** User-facing options: "street" (theme-aware) or satellite/hybrid (MapTiler hybrid-v4 when available, else Google). */
private const val OPTION_STREET = "street"
private const val OPTION_SATELLITE = "google-satellite-hybrid"
private const val SOURCE_OSM = "osm"
private const val SOURCE_OSM_DARK = "maptiler-openstreetmap-dark"
/** MapTiler Streets vector style; used for street in light mode when server provides it. */
private const val SOURCE_MAPTILER_STREETS = "maptiler-streets"
/** MapTiler Hybrid (satellite + labels); used for satellite view when server provides it instead of Google. */
private const val SOURCE_MAPTILER_HYBRID = "maptiler-hybrid-v4"

/** Utility to manage map sources, toggling, and persistence. Street: light = MapTiler streets if server provides, else OSM; dark = OSM Dark if server provides, else OSM. */
class MapSourceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    private var availableSources: List<TileSource> = listOf(
        TileSource(SOURCE_OSM, "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")),
        TileSource(OPTION_SATELLITE, "Google Satellite Hybrid", "xyz", client_config = TileClientConfig(url = "/api/tiles/google-satellite-hybrid/{z}/{x}/{y}"))
    )

    fun setSources(sources: List<TileSource>) {
        val allowedIds = setOf(SOURCE_OSM, SOURCE_OSM_DARK, SOURCE_MAPTILER_STREETS, SOURCE_MAPTILER_HYBRID, OPTION_SATELLITE)
        val filtered = sources.filter { it.id in allowedIds }
        val baseSources = mutableListOf<TileSource>()

        if (filtered.none { it.id == SOURCE_OSM }) {
            baseSources.add(TileSource(SOURCE_OSM, "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")))
        } else {
            baseSources.add(filtered.find { it.id == SOURCE_OSM }!!)
        }

        val osmDark = filtered.find { it.id == SOURCE_OSM_DARK }
        if (osmDark != null) {
            baseSources.add(osmDark)
        }
        filtered.find { it.id == SOURCE_MAPTILER_STREETS }?.let { baseSources.add(it) }

        val maptilerHybrid = filtered.find { it.id == SOURCE_MAPTILER_HYBRID }
        if (maptilerHybrid != null) {
            baseSources.add(maptilerHybrid)
        } else if (filtered.none { it.id == OPTION_SATELLITE }) {
            baseSources.add(TileSource(OPTION_SATELLITE, "Google Satellite Hybrid", "xyz", client_config = TileClientConfig(url = "/api/tiles/google-satellite-hybrid/{z}/{x}/{y}")))
        } else {
            baseSources.add(filtered.find { it.id == OPTION_SATELLITE }!!)
        }

        availableSources = baseSources
    }

    /** User-selected option: "street" or "google-satellite-hybrid". */
    fun getSelectedSourceId(): String {
        val raw = prefs.getString("selected_map_source", OPTION_STREET) ?: OPTION_STREET
        return if (raw == SOURCE_OSM) OPTION_STREET else raw
    }

    fun setSelectedSourceId(id: String) {
        val toStore = when (id) {
            SOURCE_OSM, SOURCE_OSM_DARK, SOURCE_MAPTILER_STREETS -> OPTION_STREET
            SOURCE_MAPTILER_HYBRID -> OPTION_SATELLITE
            else -> id
        }
        prefs.edit().putString("selected_map_source", toStore).apply()
    }

    /** Cycle between Street and Satellite. */
    fun getNextSourceId(): String {
        return if (getSelectedSourceId() == OPTION_STREET) OPTION_SATELLITE else OPTION_STREET
    }

    /** Effective street source: light = MapTiler streets if server provides, else OSM; dark = OSM Dark if server provides, else OSM. */
    fun getEffectiveStreetSourceId(): String {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (isDark) {
            return if (availableSources.any { it.id == SOURCE_OSM_DARK }) SOURCE_OSM_DARK else SOURCE_OSM
        }
        return if (availableSources.any { it.id == SOURCE_MAPTILER_STREETS }) SOURCE_MAPTILER_STREETS else SOURCE_OSM
    }

    /** Effective source id to load (street source or satellite: MapTiler hybrid-v4 when available, else Google). */
    fun getEffectiveSourceId(): String {
        return when (getSelectedSourceId()) {
            OPTION_STREET -> getEffectiveStreetSourceId()
            else -> if (availableSources.any { it.id == SOURCE_MAPTILER_HYBRID }) SOURCE_MAPTILER_HYBRID else OPTION_SATELLITE
        }
    }

    fun getSource(id: String): TileSource? = availableSources.find { it.id == id }

    fun isVectorSource(id: String): Boolean {
        val source = getSource(id) ?: return false
        val cfg = source.client_config
        return cfg.style_url != null || cfg.type == "maptiler"
    }

    fun getStyleUrl(id: String): String? = getSource(id)?.client_config?.style_url

    /** Resolved style URL (absolute; with server base for relative paths). */
    fun getResolvedStyleUrl(id: String): String? {
        val url = getStyleUrl(id) ?: return null
        if (!url.startsWith("/")) return url
        val baseUrl = GeovaultAuthManager.getServerUrl(context).let {
            if (it.isEmpty()) return null
            if (it.endsWith("/")) it.dropLast(1) else it
        }
        return "$baseUrl$url"
    }

    /** OSM raster URL for fallback when vector (MapTiler) street style fails to load. */
    fun getStreetFallbackRasterUrl(): String? = getRasterUrl(SOURCE_OSM)

    /** Resolved XYZ URL for raster source (absolute; with server base for relative paths). */
    fun getRasterUrl(id: String): String? {
        val source = getSource(id) ?: return null
        val url = source.client_config.url ?: return null
        if (!url.startsWith("/")) return url
        val baseUrl = GeovaultAuthManager.getServerUrl(context).let {
            if (it.isEmpty()) return null
            if (it.endsWith("/")) it.dropLast(1) else it
        }
        return "$baseUrl$url"
    }

    fun fetchSources(api: GeovaultApi, onComplete: () -> Unit) {
        api.getTileSources().enqueue(object : Callback<TileSourceResponse> {
            override fun onResponse(call: Call<TileSourceResponse>, response: Response<TileSourceResponse>) {
                response.body()?.sources?.let { setSources(it) }
                onComplete()
            }
            override fun onFailure(call: Call<TileSourceResponse>, t: Throwable) {
                setSources(listOf(
                    TileSource(SOURCE_OSM, "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")),
                    TileSource(OPTION_SATELLITE, "Google Satellite Hybrid", "xyz", client_config = TileClientConfig(url = "/api/tiles/google-satellite-hybrid/{z}/{x}/{y}"))
                ))
                onComplete()
            }
        })
    }
}
