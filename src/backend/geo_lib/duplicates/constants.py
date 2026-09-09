# Coordinate tolerance for duplicate detection (in degrees).
# 5e-6 degrees ≈ 0.5 meters. Handles GPS precision differences between files.
COORDINATE_TOLERANCE = 5e-6

GEOM_TYPE_MAPPING = {
    'point': 'Point',
    'multipoint': 'MultiPoint',
    'linestring': 'LineString',
    'multilinestring': 'MultiLineString',
    'polygon': 'Polygon',
    'multipolygon': 'MultiPolygon',
}
