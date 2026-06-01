"""
Constants and shared utilities for duplicate detection.
"""

# Coordinate tolerance for duplicate detection (in degrees)
# 5e-6 degrees ≈ 0.5 meters, which handles GPS coordinate precision differences
# between files with different decimal precision (e.g., 5 vs 14 decimal places)
COORDINATE_TOLERANCE = 5e-6

GEOM_TYPE_MAPPING = {
    'point': 'Point',
    'multipoint': 'MultiPoint',
    'linestring': 'LineString',
    'multilinestring': 'MultiLineString',
    'polygon': 'Polygon',
    'multipolygon': 'MultiPolygon',
}

# GeoJSON geometry type strings for GEOSGeometry construction (batched duplicate detection)
GEOJSON_GEOM_TYPE_NAMES = {
    'point': 'Point',
    'linestring': 'LineString',
    'polygon': 'Polygon',
    'multilinestring': 'MultiLineString',
    'multipolygon': 'MultiPolygon',
    'multipoint': 'MultiPoint',
}
