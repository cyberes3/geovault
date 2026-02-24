"""
Administrative boundary helpers.

Admin hierarchy (country, state, county, city) is provided by the is_in area server in production.
This module keeps normalize_country_name and get_admin_hierarchy (the latter for test fixtures only).
"""
from typing import Any, Dict, List, Optional, Tuple

import pycountry

from geo_lib.reverse_geocoding.geometry_helpers import point_in_polygon, point_in_bounds
from geo_lib.reverse_geocoding.osm_tags import get_name_from_tags


def _country_code_to_name(code: str) -> Optional[str]:
    """Resolve ISO 3166-1 alpha-2 country code to pycountry common name (e.g. DE -> Germany)."""
    if not code or not isinstance(code, str):
        return None
    code = code.strip().upper()
    if len(code) != 2:
        return None
    try:
        c = pycountry.countries.get(alpha_2=code)
        return c.name if c else None
    except (KeyError, AttributeError):
        return None


def normalize_country_name(name: Optional[str]) -> Optional[str]:
    """Return stripped boundary name as-is (no alias mapping)."""
    if not name or not name.strip():
        return None
    return name.strip()


def get_admin_hierarchy(
    response: Optional[Dict[str, Any]],
    latitude: float,
    longitude: float,
) -> Tuple[Dict[str, Optional[str]], List[str]]:
    """
    Parse administrative hierarchy from an Overpass response (for test fixtures only).
    Production uses the is_in area server.
    """
    result = {'country': None, 'state': None, 'county': None, 'city': None}
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
            bounds = element.get('bounds')
            if geometry and isinstance(geometry, list):
                contains = point_in_polygon(latitude, longitude, geometry)
            elif bounds:
                contains = point_in_bounds(latitude, longitude, bounds)
        if not contains:
            continue
        if admin_level == '2':
            result['country'] = normalize_country_name(name) or name
        elif admin_level == '4':
            result['state'] = name
            if result['country'] is None:
                country_name = _country_code_to_name(tags.get('is_in:country_code') or '')
                if country_name:
                    result['country'] = country_name
        elif admin_level == '6':
            result['county'] = name
            if result['country'] is None:
                country_name = _country_code_to_name(tags.get('is_in:country_code') or '')
                if country_name:
                    result['country'] = country_name
            if result['state'] is None and tags.get('is_in:state'):
                result['state'] = tags.get('is_in:state')
        elif admin_level == '8':
            result['city'] = name
    return result, errors
