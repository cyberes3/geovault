"""
Administrative boundary lookup (country, state, county, city).

This module queries OpenStreetMap data via Overpass API to find
administrative boundaries that contain a given coordinate.
"""
from typing import Dict, Optional

from geo_lib.geocoding.cache import _GEOCODING_CACHE, _get_cache_key
from geo_lib.geocoding.constants import REVERSE_GEOCODING_CACHE_TTL
from geo_lib.geocoding.overpass_api import query_overpass
from geo_lib.geocoding.osm_tags import get_name_from_tags


def get_admin_hierarchy(latitude: float, longitude: float) -> Dict[str, Optional[str]]:
    """
    Get administrative hierarchy (country, state, county, city) for a coordinate.
    
    Uses OpenStreetMap admin_level tags:
    - Level 2: Country
    - Level 4: State/Province
    - Level 6: County
    - Level 8: City/Municipality
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
    
    Returns:
        Dict with 'country', 'state', 'county', 'city' keys (values may be None)
    """
    # Check cache first
    cache_key = _get_cache_key(latitude, longitude, prefix="geocode:admin")
    cached = _GEOCODING_CACHE.get(cache_key)
    if cached is not None:
        return cached

    # Query for administrative boundaries at all levels
    query = f"""
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["admin_level"="2"]["boundary"="administrative"];
  area.a["admin_level"="4"]["boundary"="administrative"];
  area.a["admin_level"="6"]["boundary"="administrative"];
  area.a["admin_level"="8"]["boundary"="administrative"];
);
out tags;
"""

    result = {
        'country': None,
        'state': None,
        'county': None,
        'city': None
    }

    response = query_overpass(query, latitude=latitude, longitude=longitude)
    if response:
        for element in response.get('elements', []):
            tags = element.get('tags', {})
            name = get_name_from_tags(tags)
            
            if not name:
                continue

            admin_level = tags.get('admin_level')
            boundary = tags.get('boundary', '')

            # Only process administrative boundaries
            if admin_level and boundary == 'administrative':
                if admin_level == '2':
                    result['country'] = name
                elif admin_level == '4':
                    result['state'] = name
                elif admin_level == '6':
                    result['county'] = name
                elif admin_level == '8':
                    result['city'] = name

        # Cache the results
        _GEOCODING_CACHE.set(cache_key, result, REVERSE_GEOCODING_CACHE_TTL)

    return result
