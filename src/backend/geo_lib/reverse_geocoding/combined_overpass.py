"""
Single combined Overpass query for reverse geocoding.

Builds one Overpass QL query that fetches admin boundaries, protected areas,
lakes, and cities in a single request. The union must be assigned to a set
and output in a separate statement (validated syntax).
"""
from typing import Tuple, Optional, Dict, Any, List

from django.conf import settings

from geo_lib.reverse_geocoding.overpass_api import query_overpass
from geo_lib.spatial.coordinates import round_coordinate

_BBOX_HALF_DEGREES = 0.05


def build_combined_query(
    latitude: float,
    longitude: float,
    lake_radius_m: Optional[int] = None,
    city_radius_m: Optional[int] = None,
) -> str:
    """
    Build the combined Overpass QL query for one coordinate.

    Uses bbox (lat ± 0.05, lon ± 0.05) for admin and protected;
    around(lake_radius_m) for lakes and around(city_radius_m) for cities.
    Union is assigned to .all and then output with geom center.
    """
    if lake_radius_m is None:
        lake_radius_m = int(settings.LAKE_PROXIMITY_MILES * 1609.34)
    if city_radius_m is None:
        city_radius_m = int(settings.CITY_PROXIMITY_MILES * 1609.34)

    south = latitude - _BBOX_HALF_DEGREES
    north = latitude + _BBOX_HALF_DEGREES
    west = longitude - _BBOX_HALF_DEGREES
    east = longitude + _BBOX_HALF_DEGREES

    return f"""[out:json][timeout:15];
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
.all out geom center;
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
