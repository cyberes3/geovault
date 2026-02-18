"""
Administrative boundary lookup (country, state, county, city).

This module queries OpenStreetMap data via Overpass API to find
administrative boundaries that contain a given coordinate.
"""
from typing import Dict, Optional, Tuple, List

from geo_lib.reverse_geocoding.overpass_api import query_overpass
from geo_lib.reverse_geocoding.osm_tags import get_name_from_tags


def get_admin_hierarchy(latitude: float, longitude: float) -> Tuple[Dict[str, Optional[str]], List[str]]:
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
        Tuple of (admin_hierarchy_dict, list_of_error_messages)
    """
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
    errors = []

    response, error = query_overpass(query, latitude=latitude, longitude=longitude)
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
    
    if error:
        errors.append(f"Admin hierarchy lookup failed: {error}")

    return result, errors
