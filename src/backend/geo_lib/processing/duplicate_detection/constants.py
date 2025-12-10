"""
Constants and shared utilities for duplicate detection.
"""

COORDINATE_TOLERANCE = 1e-6

GEOM_TYPE_MAPPING = {
    'point': 'Point',
    'multipoint': 'MultiPoint',
    'linestring': 'LineString',
    'multilinestring': 'MultiLineString',
    'polygon': 'Polygon',
    'multipolygon': 'MultiPolygon',
}
