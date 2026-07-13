"""
Protected area classification.

Protected area data is provided by the is_in area server in production.
This module classifies the area records the area server returns into a tag prefix.
"""
from typing import Dict


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
    elif boundary != 'protected_area' and (
        'park' in protection_title or 'park' in designation or leisure == 'park'
    ):
        return "park"

    return "protected-area"
