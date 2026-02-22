"""
Nearby place searches (cities, lakes, water bodies).

Parser-only: accepts a pre-fetched Overpass response (lakes and cities)
and returns the same list shapes. Query: combined_overpass.fetch_lakes_and_cities.
"""
from typing import List, Dict, Any, Tuple, Optional

from django.conf import settings

from geo_lib.reverse_geocoding.osm_tags import get_name_from_tags
from geo_lib.spatial.haversine import haversine_distance_miles


def find_nearby_cities(
    response: Optional[Dict[str, Any]],
    latitude: float,
    longitude: float,
    threshold_miles: float = None,
) -> Tuple[List[Dict[str, Any]], List[str]]:
    """
    Parse cities/towns from Overpass response (lakes/cities query) within threshold_miles.

    Args:
        response: Lakes-and-cities Overpass response dict (with "elements") or None
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        threshold_miles: Search radius in miles (defaults to CITY_PROXIMITY_MILES setting)

    Returns:
        Tuple of (list_of_city_dicts, list_of_error_messages)
    """
    if threshold_miles is None:
        threshold_miles = settings.CITY_PROXIMITY_MILES

    cities = []
    errors = []

    if not response:
        return cities, errors

    for element in response.get('elements', []):
        if element.get('type') != 'node':
            continue
        tags = element.get('tags', {})
        if not tags.get('place') or tags.get('place', '') not in ('town', 'city', 'village'):
            continue
        name = get_name_from_tags(tags)
        lat = element.get('lat')
        lon = element.get('lon')
        if not name or lat is None or lon is None:
            continue
        distance = haversine_distance_miles(latitude, longitude, lat, lon)
        if distance <= threshold_miles:
            cities.append({
                'name': name,
                'distance_miles': distance,
                'place_type': tags.get('place', '')
            })

    cities.sort(key=lambda x: x['distance_miles'])
    return cities, errors


def search_nearby_lakes(
    response: Optional[Dict[str, Any]],
    latitude: float,
    longitude: float,
    proximity_miles: float = None,
) -> Tuple[List[Dict[str, Any]], List[str]]:
    """
    Parse lakes and water bodies from Overpass response (lakes/cities query) within proximity_miles.

    Args:
        response: Lakes-and-cities Overpass response dict (with "elements") or None
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        proximity_miles: Distance threshold in miles (defaults to LAKE_PROXIMITY_MILES setting)

    Returns:
        Tuple of (list_of_lake_dicts, list_of_error_messages)
    """
    if proximity_miles is None:
        proximity_miles = settings.LAKE_PROXIMITY_MILES

    lakes = []
    errors = []

    if not response:
        return lakes, errors

    for element in response.get('elements', []):
        if element.get('type') not in ('way', 'relation'):
            continue
        tags = element.get('tags', {})
        name = get_name_from_tags(tags)
        if not name:
            continue
        water_type = tags.get('water', '')
        if water_type not in ('lake', 'reservoir', 'pond', ''):
            continue

        lat = element.get('lat')
        lon = element.get('lon')
        center = element.get('center', {})
        if lat is None:
            lat = center.get('lat')
        if lon is None:
            lon = center.get('lon')
        if lat is None or lon is None:
            continue

        distance = haversine_distance_miles(latitude, longitude, lat, lon)
        if distance <= proximity_miles:
            lakes.append({
                'name': name,
                'distance_miles': distance,
                'water_type': water_type or 'water'
            })

    lakes.sort(key=lambda x: x['distance_miles'])
    return lakes, errors
