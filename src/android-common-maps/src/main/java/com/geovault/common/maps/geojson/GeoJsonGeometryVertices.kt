package com.geovault.common.maps.geojson

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.android.geometry.LatLng

/**
 * Walks a GeoJSON geometry object and yields every vertex as a `(lat, lon)` pair.
 *
 * Unknown geometry types, malformed arrays, and extra-wide coordinate tuples (altitude)
 * degrade to an empty list so camera-fit callers can fall back instead of crashing.
 * The `(0, 0)` null-island sentinel used by some KML placeholders is omitted.
 */
object GeoJsonGeometryVertices {

    fun vertices(geometryJson: String?): List<Pair<Double, Double>> {
        if (geometryJson.isNullOrBlank()) return emptyList()
        val element = runCatching { JSON.parseToJsonElement(geometryJson) }.getOrNull() ?: return emptyList()
        val obj = element as? JsonObject ?: return emptyList()
        val type = obj["type"]?.jsonPrimitive?.content ?: return emptyList()
        val coords = obj["coordinates"] as? JsonArray ?: return emptyList()
        val sink = mutableListOf<Pair<Double, Double>>()
        collect(type, coords, sink)
        return sink
    }

    fun first(geometryJson: String?): Pair<Double, Double>? = vertices(geometryJson).firstOrNull()

    fun latLngs(geometryJson: String?): List<LatLng> =
        vertices(geometryJson).map { (lat, lon) -> LatLng(lat, lon) }

    private fun collect(
        type: String,
        coords: JsonArray,
        sink: MutableList<Pair<Double, Double>>,
    ) {
        when (type) {
            "Point" -> coords.appendLonLat(sink)
            "MultiPoint", "LineString" -> coords.forEachAsArray { it.appendLonLat(sink) }
            "MultiLineString", "Polygon" -> coords.forEachAsArray { ring ->
                ring.forEachAsArray { it.appendLonLat(sink) }
            }
            "MultiPolygon" -> coords.forEachAsArray { polygon ->
                polygon.forEachAsArray { ring ->
                    ring.forEachAsArray { it.appendLonLat(sink) }
                }
            }
        }
    }

    private fun JsonArray.appendLonLat(sink: MutableList<Pair<Double, Double>>) {
        val lon = this.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return
        val lat = this.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return
        if (lon == 0.0 && lat == 0.0) return
        sink += lat to lon
    }

    private inline fun JsonArray.forEachAsArray(block: (JsonArray) -> Unit) {
        for (element in this) {
            val array = element as? JsonArray ?: continue
            block(array)
        }
    }

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
