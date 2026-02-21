"""
Geometry helpers for reverse geocoding (point-in-polygon for admin/protected areas).

Used when parsing combined Overpass responses: elements may include "geometry"
(list of {lat, lon}) when using "out geom". Type "area" elements are treated as
containing the point without geometry (legacy is_in behavior).
"""
from typing import List, Dict

from django.contrib.gis.geos import Point, Polygon


def point_in_polygon(lat: float, lon: float, geometry: List[Dict[str, float]]) -> bool:
    """
    Test if point (lat, lon) is inside a polygon.

    Args:
        lat: Point latitude
        lon: Point longitude
        geometry: List of {"lat": float, "lon": float} (Overpass "geometry" format)

    Returns:
        True if point is inside the polygon (or on boundary), False otherwise.
    """
    if not geometry or len(geometry) < 3:
        return False
    try:
        # GEOS uses (x, y) = (lon, lat)
        coords = [(float(g['lon']), float(g['lat'])) for g in geometry]
        if coords[0] != coords[-1]:
            coords.append(coords[0])
        poly = Polygon(coords)
        point = Point(lon, lat)
        return poly.contains(point)
    except Exception:
        return False
