"""
Protected area classification and parsing.

Protected area data is provided by the is_in area server in production.
This module provides classify_protected_area() and get_protected_areas() (for test fixtures only).
"""
from typing import Any, Dict, List, Optional, Tuple

from geo_lib.reverse_geocoding.geometry_helpers import point_in_polygon, point_in_bounds
from geo_lib.reverse_geocoding.osm_tags import get_name_from_tags


def get_protected_areas(
    response: Optional[Dict[str, Any]],
    latitude: float,
    longitude: float,
) -> Tuple[List[Dict[str, str]], List[str]]:
    """
    Parse protected areas from an Overpass response (for test fixtures only).
    Production uses the is_in area server.
    """
    protected_areas = []
    errors = []
    if not response:
        return protected_areas, errors
    for element in response.get('elements', []):
        tags = element.get('tags', {})
        name = get_name_from_tags(tags)
        if not name:
            continue
        boundary = tags.get('boundary', '')
        leisure = tags.get('leisure', '')
        landuse = tags.get('landuse', '')
        if (boundary != 'protected_area' and boundary != 'national_park' and
                leisure != 'nature_reserve' and leisure != 'park' and
                landuse != 'recreation_ground'):
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
        area_info = {
            'name': name,
            'protection_title': tags.get('protection_title', ''),
            'protect_class': tags.get('protect_class', ''),
            'designation': tags.get('designation', ''),
            'operator': tags.get('operator', ''),
            'leisure': leisure,
            'landuse': landuse,
            'boundary': boundary
        }
        protected_areas.append(area_info)
    return protected_areas, errors


def classify_protected_area(area: Dict[str, str]) -> str:
    """
    Classify a protected area into a specific category based on OSM tags.

    Returns a tag prefix like "national-park", "state-park", "wilderness", "park", etc.
    """
    protection_title = area.get('protection_title', '').lower()
    designation = area.get('designation', '').lower()
    operator = area.get('operator', '').lower()
    boundary = area.get('boundary', '').lower()
    leisure = area.get('leisure', '').lower()

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
    elif leisure == 'park' and boundary != 'protected_area':
        return "park"
    elif 'park' in protection_title or 'park' in designation or leisure == 'park':
        return "park"

    return "protected-area"
