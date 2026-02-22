"""
Overpass query for reverse geocoding: lakes and cities only.

Admin and protected areas are provided by the is_in area server.
"""
from typing import Any, Dict, List, Optional, Tuple

from django.conf import settings

from geo_lib.reverse_geocoding.overpass_api import query_overpass
from geo_lib.spatial.coordinates import round_coordinate


def build_lakes_and_cities_query(
    latitude: float,
    longitude: float,
    lake_radius_m: Optional[int] = None,
    city_radius_m: Optional[int] = None,
) -> str:
    """
    Build Overpass QL query for lakes and cities (around radius only).

    Used for proximity tags; admin and protected areas come from the areas server.
    """
    if lake_radius_m is None:
        lake_radius_m = int(settings.LAKE_PROXIMITY_MILES * 1609.34)
    if city_radius_m is None:
        city_radius_m = int(settings.CITY_PROXIMITY_MILES * 1609.34)

    return f"""[out:json][timeout:60];
(
  way["natural"="water"]["name"](around:{lake_radius_m},{latitude},{longitude});
  relation["natural"="water"]["name"](around:{lake_radius_m},{latitude},{longitude});
  way["water"="lake"]["name"](around:{lake_radius_m},{latitude},{longitude});
  relation["water"="lake"]["name"](around:{lake_radius_m},{latitude},{longitude});
  node["place"~"town|city|village"](around:{city_radius_m},{latitude},{longitude});
)->.all;
.all out tags geom center bb;
"""


def fetch_lakes_and_cities(
    latitude: float,
    longitude: float,
) -> Tuple[Optional[Dict[str, Any]], List[str]]:
    """
    Execute Overpass query for lakes and cities near the given coordinate.

    Uses rounded coordinates so cache keys match for nearby points.
    Returns (response_dict or None, list of error messages).
    """
    lat_rounded, lon_rounded = round_coordinate(latitude, longitude)
    query = build_lakes_and_cities_query(lat_rounded, lon_rounded)
    response, error = query_overpass(query, latitude=latitude, longitude=longitude)
    errors = []
    if error:
        errors.append(error)
    return response, errors
