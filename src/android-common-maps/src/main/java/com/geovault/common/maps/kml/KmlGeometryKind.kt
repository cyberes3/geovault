package com.geovault.common.maps.kml

/**
 * Coarse classification of a GeoJSON / KML geometry type string.
 *
 * Shared by survey list grouping, map rendering, and filter visibility so Point vs
 * linework decisions cannot drift across call sites.
 */
enum class KmlGeometryKind {
    Point,
    Line,
    Polygon,
    Unknown,
    ;

    /** True for open paths and closed areas (everything that is not a point). */
    val isLinework: Boolean
        get() = this == Line || this == Polygon

    companion object {
        fun fromType(geometryType: String?): KmlGeometryKind =
            when (geometryType?.trim()) {
                "Point", "MultiPoint" -> Point
                "LineString", "MultiLineString" -> Line
                "Polygon", "MultiPolygon" -> Polygon
                else -> Unknown
            }
    }
}
