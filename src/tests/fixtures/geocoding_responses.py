"""
Overpass API response fixtures for reverse geocoding tests.

- Lakes-and-cities Overpass responses: fixtures/combined_overpass/{lat}_{lon}.json
- Areas server responses (admin_hierarchy + protected_areas): fixtures/areas_server/{lat}_{lon}.json
"""
import json
import os
import re
from typing import Optional

from geo_lib.spatial.coordinates import round_coordinate


# Real Overpass API response for retry tests (test_error_recovery/test_overpass_retry.py)
# Captured from https://overpass.private.coffee/api/interpreter on January 22, 2026
# Query: [out:json];node(around:1000,37.7749,-122.4194);out;
RETRY_TEST_SUCCESS_RESPONSE = {
    "version": 0.6,
    "generator": "Overpass API 0.7.61.8 b1080abd",
    "osm3s": {
        "timestamp_osm_base": "2026-01-22T03:16:22Z",
        "copyright": "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."
    },
    "elements": [
        {
            "type": "node",
            "id": 61675193,
            "lat": 37.7721282,
            "lon": -122.4227728,
            "tags": {
                "addr:city": "San Francisco",
                "addr:housenumber": "4",
                "addr:postcode": "94103",
                "addr:state": "CA",
                "addr:street": "Valencia Street",
                "amenity": "bar",
                "check_date": "2025-12-02",
                "level": "0",
                "name": "Martuni's",
                "outdoor_seating": "no",
                "phone": "+1-415-241-0205",
                "smoking": "no",
                "wikidata": "Q108821598"
            }
        },
        {
            "type": "node",
            "id": 65280134,
            "lat": 37.7749327,
            "lon": -122.4084965
        },
        {
            "type": "node",
            "id": 65280136,
            "lat": 37.7747987,
            "lon": -122.4083293
        },
        {
            "type": "node",
            "id": 65281164,
            "lat": 37.7746045,
            "lon": -122.4259102,
            "tags": {
                "highway": "traffic_signals",
                "turn_restrictions": "no"
            }
        },
        {
            "type": "node",
            "id": 65281218,
            "lat": 37.7718656,
            "lon": -122.423325,
            "tags": {
                "highway": "traffic_signals",
                "traffic_signals": "traffic_lights"
            }
        }
    ]
}

EMPTY_RESPONSE = {
    "elements": []
}


def _is_lakes_and_cities_query(query: str) -> bool:
    """Return True if query is the lakes-and-cities-only Overpass query."""
    has_water = 'natural"="water' in query or 'water"="lake' in query
    has_place = 'place"~"town' in query or 'place"~"city' in query
    has_around = 'around:' in query
    return bool(has_water and has_place and has_around)


def _load_combined_response_from_file(lat: float, lon: float) -> dict:
    """Load lakes-and-cities Overpass response from fixtures/combined_overpass/{lat}_{lon}.json."""
    lat_r, lon_r = round_coordinate(lat, lon)
    filename = f"{lat_r}_{lon_r}.json"
    dir_path = os.path.join(os.path.dirname(__file__), 'combined_overpass')
    file_path = os.path.join(dir_path, filename)
    if not os.path.isfile(file_path):
        return EMPTY_RESPONSE
    with open(file_path, 'r', encoding='utf-8') as f:
        return json.load(f)


def get_combined_fixture(lat: float, lon: float) -> dict:
    """Load lakes-and-cities Overpass fixture for (lat, lon). Used by tests that need Overpass response."""
    return _load_combined_response_from_file(lat, lon)


def get_areas_fixture(lat: float, lon: float) -> Optional[dict]:
    """
    Load areas server response fixture for (lat, lon).
    Returns {"admin_hierarchy": {...}, "protected_areas": [...]} or None if file missing/invalid.
    """
    lat_r, lon_r = round_coordinate(lat, lon)
    filename = f"{lat_r}_{lon_r}.json"
    dir_path = os.path.join(os.path.dirname(__file__), 'areas_server')
    file_path = os.path.join(dir_path, filename)
    if not os.path.isfile(file_path):
        return None
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        if isinstance(data, dict) and 'admin_hierarchy' in data and 'protected_areas' in data:
            return data
    except (json.JSONDecodeError, OSError):
        pass
    return None


def get_mock_overpass_response(query: str) -> dict:
    """
    Return mock Overpass response for tests.

    For lakes-and-cities query: loads fixtures/combined_overpass/{lat}_{lon}.json.
    Otherwise returns EMPTY_RESPONSE.
    """
    if _is_lakes_and_cities_query(query):
        coord_match = re.search(r'around:(\d+),([-\d.]+),([-\d.]+)', query)
        if not coord_match:
            return EMPTY_RESPONSE
        try:
            lat = float(coord_match.group(2))
            lon = float(coord_match.group(3))
        except ValueError:
            return EMPTY_RESPONSE
        return _load_combined_response_from_file(lat, lon)
    return EMPTY_RESPONSE
