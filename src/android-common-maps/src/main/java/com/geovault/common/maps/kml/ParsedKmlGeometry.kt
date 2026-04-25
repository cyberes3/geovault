package com.geovault.common.maps.kml

/**
 * Geometry extracted from a KML subtree, ready to map into GeoJSON-style coordinate trees.
 */
sealed class ParsedKmlGeometry {

    data class Point(
        val position: KmlPosition,
    ) : ParsedKmlGeometry()

    data class LineString(
        val positions: List<KmlPosition>,
    ) : ParsedKmlGeometry()

    /**
     * @param rings Outer ring first, then inner rings (holes) in document order.
     */
    data class Polygon(
        val rings: List<List<KmlPosition>>,
    ) : ParsedKmlGeometry()
}
