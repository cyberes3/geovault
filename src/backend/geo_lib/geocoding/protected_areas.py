"""
Protected area lookup (national parks, forests, wilderness areas, etc.).

This module queries OpenStreetMap data to find protected areas that
contain a given coordinate, including national parks, state parks,
wilderness areas, and other protected lands.
"""
from typing import List, Dict

from geo_lib.geocoding.cache import _REVERSE_GEOCODING_CACHE, _get_cache_key
from geo_lib.geocoding.constants import REVERSE_GEOCODING_CACHE_TTL
from geo_lib.geocoding.overpass_api import query_overpass
from geo_lib.geocoding.osm_tags import get_name_from_tags


def get_protected_areas(latitude: float, longitude: float) -> List[Dict[str, str]]:
    """
    Get all protected areas containing a point.
    
    Returns information about national parks, state parks, wilderness areas,
    national forests, and other protected lands.
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
    
    Returns:
        List of protected area dicts with name and classification info:
        - name: Name of the protected area
        - protection_title: Official protection title
        - protect_class: IUCN protection class
        - designation: Official designation
        - operator: Operating agency
        - leisure: Leisure tag (e.g., nature_reserve)
        - boundary: Boundary type (e.g., protected_area, national_park)
    """
    # Check cache first
    cache_key = _get_cache_key(latitude, longitude, prefix="reverse_geocode:protected")
    cached = _REVERSE_GEOCODING_CACHE.get(cache_key)
    if cached is not None:
        return cached

    # Query for protected areas
    query = f"""
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
  area.a["boundary"="national_park"];
);
out tags;
"""

    protected_areas = []

    response = query_overpass(query, latitude=latitude, longitude=longitude)
    if response:
        for element in response.get('elements', []):
            tags = element.get('tags', {})
            name = get_name_from_tags(tags)
            
            if not name:
                continue

            boundary = tags.get('boundary', '')

            # Check if this is a protected area
            if (boundary == 'protected_area' or 
                boundary == 'national_park' or
                tags.get('leisure') == 'nature_reserve'):
                
                area_info = {
                    'name': name,
                    'protection_title': tags.get('protection_title', ''),
                    'protect_class': tags.get('protect_class', ''),
                    'designation': tags.get('designation', ''),
                    'operator': tags.get('operator', ''),
                    'leisure': tags.get('leisure', ''),
                    'boundary': boundary
                }
                protected_areas.append(area_info)

        # Cache the results
        _REVERSE_GEOCODING_CACHE.set(cache_key, protected_areas, REVERSE_GEOCODING_CACHE_TTL)

    return protected_areas


def classify_protected_area(area: Dict[str, str]) -> str:
    """
    Classify a protected area into a specific category based on OSM tags.
    
    Returns a tag prefix like "national-park", "state-park", "wilderness", etc.
    based on the area's protection_title, designation, operator, and boundary tags.
    
    Args:
        area: Protected area dict with classification info
    
    Returns:
        Tag prefix string (e.g., "national-park", "state-park", "wilderness")
    """
    protection_title = area.get('protection_title', '').lower()
    designation = area.get('designation', '').lower()
    operator = area.get('operator', '').lower()
    boundary = area.get('boundary', '').lower()

    # Check in priority order (most specific first)
    if 'national forest' in protection_title:
        return "national-forest"
    elif 'wilderness' in protection_title or 'wilderness' in designation:
        return "wilderness"
    elif 'national park' in protection_title or 'national park' in designation or 'national park' in boundary:
        return "national-park"
    elif 'national monument' in protection_title or 'national monument' in designation:
        return "national-monument"
    elif 'national wildlife refuge' in protection_title or 'wildlife refuge' in protection_title:
        return "national-wildlife-refuge"
    elif 'national recreation area' in protection_title or 'national recreation area' in designation:
        return "national-recreation-area"
    elif 'national historic' in protection_title or 'national historic' in designation:
        return "national-historic-site"
    elif 'national seashore' in protection_title or 'national seashore' in designation:
        return "national-seashore"
    elif 'national lakeshore' in protection_title or 'national lakeshore' in designation:
        return "national-lakeshore"
    elif 'state park' in protection_title or 'state park' in designation or 'state park' in operator:
        return "state-park"
    
    # Default fallback
    return "protected-area"
