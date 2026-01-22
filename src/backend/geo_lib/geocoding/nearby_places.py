"""
Nearby place searches (cities, lakes, water bodies).

This module provides proximity-based searches for places near a coordinate,
including cities, towns, villages, and water bodies like lakes and reservoirs.
"""
from typing import List, Dict, Any, Tuple

from django.conf import settings

from geo_lib.geocoding.overpass_api import query_overpass
from geo_lib.geocoding.osm_tags import get_name_from_tags
from geo_lib.spatial.haversine import haversine_distance_miles


def find_nearby_cities(
    latitude: float,
    longitude: float,
    threshold_miles: float = None
) -> Tuple[List[Dict[str, Any]], List[str]]:
    """
    Find cities/towns within threshold_miles of a point.
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        threshold_miles: Search radius in miles (defaults to CITY_PROXIMITY_MILES setting)
    
    Returns:
        Tuple of (list_of_city_dicts, list_of_error_messages)
    """
    if threshold_miles is None:
        threshold_miles = settings.CITY_PROXIMITY_MILES
    
    # Convert miles to meters (1 mile = 1609.34 meters)
    radius_meters = int(threshold_miles * 1609.34)

    query = f"""
[out:json];
(
  node["place"~"town|city|village"](around:{radius_meters},{latitude},{longitude});
);
out center;
"""

    cities = []
    errors = []
    response, error = query_overpass(query, latitude=latitude, longitude=longitude)
    if response:
        for element in response.get('elements', []):
            tags = element.get('tags', {})
            name = get_name_from_tags(tags)
            lat = element.get('lat')
            lon = element.get('lon')

            if name and lat is not None and lon is not None:
                distance = haversine_distance_miles(latitude, longitude, lat, lon)
                if distance <= threshold_miles:
                    cities.append({
                        'name': name,
                        'distance_miles': distance,
                        'place_type': tags.get('place', '')
                    })

        # Sort by distance
        cities.sort(key=lambda x: x['distance_miles'])
    
    if error:
        errors.append(f"Nearby cities search failed: {error}")

    return cities, errors


def search_nearby_lakes(
    latitude: float,
    longitude: float,
    proximity_miles: float = None
) -> Tuple[List[Dict[str, Any]], List[str]]:
    """
    Search for lakes and water bodies within proximity_miles of a point.
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        proximity_miles: Distance threshold in miles (defaults to LAKE_PROXIMITY_MILES setting)
    
    Returns:
        Tuple of (list_of_lake_dicts, list_of_error_messages)
    """
    if proximity_miles is None:
        proximity_miles = settings.LAKE_PROXIMITY_MILES
    
    # Convert miles to meters
    radius_meters = int(proximity_miles * 1609.34)

    query = f"""
[out:json];
(
  way["natural"="water"]["name"](around:{radius_meters},{latitude},{longitude});
  relation["natural"="water"]["name"](around:{radius_meters},{latitude},{longitude});
  way["water"="lake"]["name"](around:{radius_meters},{latitude},{longitude});
  relation["water"="lake"]["name"](around:{radius_meters},{latitude},{longitude});
);
out tags center;
"""

    lakes = []
    errors = []
    response, error = query_overpass(query, latitude=latitude, longitude=longitude)
    if response:
        for element in response.get('elements', []):
            tags = element.get('tags', {})
            name = get_name_from_tags(tags)
            water_type = tags.get('water', '')

            # Only include lakes, not rivers/streams
            if name and water_type in ['lake', 'reservoir', 'pond', '']:
                # Get center coordinates
                lat = element.get('lat')
                lon = element.get('lon')
                center = element.get('center', {})
                if not lat:
                    lat = center.get('lat')
                    lon = center.get('lon')

                if lat and lon:
                    distance = haversine_distance_miles(latitude, longitude, lat, lon)
                    if distance <= proximity_miles:
                        lakes.append({
                            'name': name,
                            'distance_miles': distance,
                            'water_type': water_type or 'water'
                        })

        # Sort by distance
        lakes.sort(key=lambda x: x['distance_miles'])
    
    if error:
        errors.append(f"Nearby lakes search failed: {error}")

    return lakes, errors
