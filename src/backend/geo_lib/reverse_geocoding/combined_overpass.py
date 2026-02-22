"""
Single combined Overpass query for reverse geocoding.

Builds one Overpass QL query that fetches admin boundaries (with bounds), protected
areas, lakes, and cities in a single request (bbox + around only). Country and state
come from the combined response; admin parsing uses geometry or bounds to pick the
correct state when multiple are in the bbox.
"""
from typing import Tuple, Optional, Dict, Any, List

from django.conf import settings

from geo_lib.reverse_geocoding.overpass_api import query_overpass
from geo_lib.spatial.coordinates import round_coordinate

# Bbox half-side in degrees. Large enough that big parks (RMNP, Yellowstone) have
# at least one boundary member in the box; we still filter by point-in-polygon.
_BBOX_HALF_DEGREES = 0.5  # 0.25 misses large parks (e.g. Yellowstone) – Overpass indexes by member nodes


def build_combined_query(
    latitude: float,
    longitude: float,
    lake_radius_m: Optional[int] = None,
    city_radius_m: Optional[int] = None,
) -> str:
    """
    Build the combined Overpass QL query for one coordinate.

    Uses bbox (lat ± 0.5°, lon ± 0.5°) for admin and protected so large
    relations (e.g. national parks) are returned when any member is in the box;
    point-in-polygon filtering keeps only areas containing the point.
    Lakes and cities use around(radius). Union is .all, output with geom center.
    """
    if lake_radius_m is None:
        lake_radius_m = int(settings.LAKE_PROXIMITY_MILES * 1609.34)
    if city_radius_m is None:
        city_radius_m = int(settings.CITY_PROXIMITY_MILES * 1609.34)

    south = latitude - _BBOX_HALF_DEGREES
    north = latitude + _BBOX_HALF_DEGREES
    west = longitude - _BBOX_HALF_DEGREES
    east = longitude + _BBOX_HALF_DEGREES

    # 60s timeout; larger bbox (0.15°) can be slow on busy Overpass instances
    return f"""[out:json][timeout:60];
(
  relation["boundary"="administrative"]["admin_level"~"2|4|6|8"]({south},{west},{north},{east});
  relation["boundary"="protected_area"]({south},{west},{north},{east});
  relation["leisure"="nature_reserve"]({south},{west},{north},{east});
  relation["boundary"="national_park"]({south},{west},{north},{east});
  relation["leisure"="park"]({south},{west},{north},{east});
  relation["landuse"="recreation_ground"]({south},{west},{north},{east});
  way["boundary"="protected_area"]({south},{west},{north},{east});
  way["leisure"="park"]({south},{west},{north},{east});
  way["landuse"="recreation_ground"]({south},{west},{north},{east});
  way["natural"="water"]["name"](around:{lake_radius_m},{latitude},{longitude});
  relation["natural"="water"]["name"](around:{lake_radius_m},{latitude},{longitude});
  way["water"="lake"]["name"](around:{lake_radius_m},{latitude},{longitude});
  relation["water"="lake"]["name"](around:{lake_radius_m},{latitude},{longitude});
  node["place"~"town|city|village"](around:{city_radius_m},{latitude},{longitude});
)->.all;
.all out tags geom center bb;
"""


def fetch_combined(
    latitude: float,
    longitude: float,
) -> Tuple[Optional[Dict[str, Any]], List[str]]:
    """
    Execute a single combined Overpass query for the given coordinate.

    Uses rounded coordinates for the query so cache keys match for nearby points.
    Returns (response_dict or None, list of error messages).
    Caching is handled by query_overpass.
    """
    lat_rounded, lon_rounded = round_coordinate(latitude, longitude)
    query = build_combined_query(lat_rounded, lon_rounded)
    response, error = query_overpass(query, latitude=latitude, longitude=longitude)
    errors = []
    if error:
        errors.append(error)
    return response, errors
