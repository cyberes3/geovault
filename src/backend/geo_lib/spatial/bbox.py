from typing import Dict, Any, Optional


def get_feature_bounding_box_center(feature: Dict[str, Any]) -> Optional[tuple]:
    """
    Calculate the bounding box center (lat, lon) for a feature.

    Args:
        feature: GeoJSON feature dictionary

    Returns:
        Tuple of (lat, lon) center coordinates, or None if feature has no valid geometry
    """
    geometry = feature.get('geometry', {})
    if not geometry:
        return None

    geom_type = geometry.get('type', '').lower()
    coordinates = geometry.get('coordinates')

    if not coordinates:
        return None

    all_points = []

    if geom_type == 'point':
        # Point: [lon, lat] or [lon, lat, elevation]
        if isinstance(coordinates, list) and len(coordinates) >= 2:
            all_points.append(coordinates)

    elif geom_type == 'multipoint':
        # MultiPoint: [[lon, lat], [lon, lat], ...]
        if isinstance(coordinates, list):
            for point in coordinates:
                if isinstance(point, list) and len(point) >= 2:
                    all_points.append(point)

    elif geom_type == 'linestring':
        # LineString: [[lon, lat], [lon, lat], ...]
        if isinstance(coordinates, list):
            for point in coordinates:
                if isinstance(point, list) and len(point) >= 2:
                    all_points.append(point)

    elif geom_type == 'multilinestring':
        # MultiLineString: [[[lon, lat], ...], [[lon, lat], ...], ...]
        if isinstance(coordinates, list):
            for linestring in coordinates:
                if isinstance(linestring, list):
                    for point in linestring:
                        if isinstance(point, list) and len(point) >= 2:
                            all_points.append(point)

    elif geom_type == 'polygon':
        # Polygon: [[[lon, lat], ...], [[lon, lat], ...], ...] (exterior ring + holes)
        if isinstance(coordinates, list):
            for ring in coordinates:
                if isinstance(ring, list):
                    for point in ring:
                        if isinstance(point, list) and len(point) >= 2:
                            all_points.append(point)

    elif geom_type == 'multipolygon':
        # MultiPolygon: [[[[lon, lat], ...], ...], [[[lon, lat], ...], ...], ...]
        if isinstance(coordinates, list):
            for polygon in coordinates:
                if isinstance(polygon, list):
                    for ring in polygon:
                        if isinstance(ring, list):
                            for point in ring:
                                if isinstance(point, list) and len(point) >= 2:
                                    all_points.append(point)

    # Calculate bounding box from all points
    if not all_points:
        return None

    # Extract lons and lats (GeoJSON uses [lon, lat] format)
    lons = [point[0] for point in all_points if isinstance(point[0], (int, float))]
    lats = [point[1] for point in all_points if isinstance(point[1], (int, float))]

    if not lons or not lats:
        return None

    # Calculate center
    center_lon = (min(lons) + max(lons)) / 2.0
    center_lat = (min(lats) + max(lats)) / 2.0

    return center_lat, center_lon
