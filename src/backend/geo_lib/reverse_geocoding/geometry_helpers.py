"""
Geometry helpers for reverse geocoding (point-in-polygon).

Used when parsing Overpass-style responses: elements may include "geometry"
(list of {lat, lon}) or "bounds". Type "area" elements are treated as containing the point when geometry is absent.
"""
from typing import List, Dict, Any

from django.contrib.gis.geos import Point, Polygon


def point_in_bounds(lat: float, lon: float, bounds: Any) -> bool:
    """
    True if (lat, lon) is inside bounds.

    Bounds may be minlat/maxlat/minlon/maxlon (Overpass) or south/west/north/east.
    """
    if not bounds or not isinstance(bounds, dict):
        return False
    minlat = bounds.get("minlat") or bounds.get("south")
    maxlat = bounds.get("maxlat") or bounds.get("north")
    minlon = bounds.get("minlon") or bounds.get("west")
    maxlon = bounds.get("maxlon") or bounds.get("east")
    if None in (minlat, maxlat, minlon, maxlon):
        return False
    return float(minlat) <= lat <= float(maxlat) and float(minlon) <= lon <= float(maxlon)


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
