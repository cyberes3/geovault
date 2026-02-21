"""
Administrative boundary lookup (country, state, county, city).

Parser-only: accepts a pre-fetched combined Overpass response and returns
the same hierarchy dict. The combined query is executed by combined_overpass.fetch_combined.
"""
from typing import Dict, Optional, Tuple, List, Any

from geo_lib.reverse_geocoding.geometry_helpers import point_in_polygon
from geo_lib.reverse_geocoding.osm_tags import get_name_from_tags


def get_admin_hierarchy(
    response: Optional[Dict[str, Any]],
    latitude: float,
    longitude: float,
) -> Tuple[Dict[str, Optional[str]], List[str]]:
    """
    Parse administrative hierarchy (country, state, county, city) from combined Overpass response.

    Uses OpenStreetMap admin_level tags:
    - Level 2: Country
    - Level 4: State/Province
    - Level 6: County
    - Level 8: City/Municipality

    Elements of type "area" (legacy is_in result) are treated as containing the point.
    Elements of type "relation" or "way" with "geometry" use point-in-polygon.

    Args:
        response: Combined Overpass response dict (with "elements") or None
        latitude: Latitude coordinate
        longitude: Longitude coordinate

    Returns:
        Tuple of (admin_hierarchy_dict, list_of_error_messages)
    """
    result = {
        'country': None,
        'state': None,
        'county': None,
        'city': None
    }
    errors = []

    if not response:
        return result, errors

    for element in response.get('elements', []):
        tags = element.get('tags', {})
        name = get_name_from_tags(tags)
        if not name:
            continue

        admin_level = tags.get('admin_level')
        boundary = tags.get('boundary', '')
        if not admin_level or boundary != 'administrative':
            continue

        elem_type = element.get('type', '')
        contains = False
        if elem_type == 'area':
            contains = True
        elif elem_type in ('relation', 'way'):
            geometry = element.get('geometry')
            if geometry and isinstance(geometry, list):
                contains = point_in_polygon(latitude, longitude, geometry)
            else:
                # No geometry: assume contains (combined query already filters by bbox)
                contains = True

        if not contains:
            continue

        if admin_level == '2':
            result['country'] = name
        elif admin_level == '4':
            result['state'] = name
        elif admin_level == '6':
            result['county'] = name
        elif admin_level == '8':
            result['city'] = name

    return result, errors
