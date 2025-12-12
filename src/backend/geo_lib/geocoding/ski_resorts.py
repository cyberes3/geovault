"""
Ski resort lookup using a pre-compiled database.

This module provides ski resort detection using a local JSON database
of major ski resorts with bounding boxes, avoiding the limitations of
OpenStreetMap data which often lacks proper ski resort tagging.
"""
import json
from pathlib import Path
from threading import Lock
from typing import List, Dict, Any

from geo_lib.geocoding.cache import _REVERSE_GEOCODING_CACHE, _get_cache_key
from geo_lib.geocoding.constants import REVERSE_GEOCODING_CACHE_TTL
from geo_lib.logging.console import get_tagged_logger
from geo_lib.spatial.haversine import haversine_distance_miles

_SKI_RESORTS = None
_SKI_RESORTS_LOCK = Lock()
_logger = get_tagged_logger()


def load_ski_resorts() -> List[Dict[str, Any]]:
    """Load ski resort database from JSON file with thread-safe initialization."""
    global _SKI_RESORTS

    # Fast path: if already loaded, return immediately without acquiring lock
    if _SKI_RESORTS is not None:
        return _SKI_RESORTS

    # Slow path: acquire lock and load data (only first thread will do this)
    with _SKI_RESORTS_LOCK:
        # Double-check after acquiring lock (another thread may have loaded it)
        if _SKI_RESORTS is None:
            data_dir = Path(__file__).parent.parent / 'data'
            ski_resorts_file = data_dir / 'ski_resorts.json'
            with open(ski_resorts_file, 'r', encoding='utf-8') as f:
                data = json.load(f)
                _SKI_RESORTS = data.get('ski_resorts', [])
                _logger.info(f"Loaded {len(_SKI_RESORTS)} ski resorts from database")

    return _SKI_RESORTS


def search_nearby_ski_resorts(latitude: float, longitude: float, proximity_miles: float = 2.0) -> List[Dict[str, Any]]:
    """
    Search for ski resorts within proximity_miles of a point using local database.

    Uses a pre-compiled database of major ski resorts with bounding boxes.
    First checks if point is inside any resort bbox, then finds nearest resorts.

    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        proximity_miles: Distance threshold in miles (not used for bbox check)

    Returns:
        List of ski resort dicts with name, distance, country, and state
    """
    # Check cache first
    cache_key = _get_cache_key(latitude, longitude, prefix="reverse_geocode:ski")
    cached = _REVERSE_GEOCODING_CACHE.get(cache_key)
    if cached is not None:
        return cached

    ski_resorts_data = load_ski_resorts()
    matching_resorts = []

    # Check if point is inside any resort bbox
    for resort in ski_resorts_data:
        bbox = resort.get('bbox', {})
        if not bbox:
            continue

        # Check if point is inside bbox
        if (bbox['min_lat'] <= latitude <= bbox['max_lat'] and
                bbox['min_lon'] <= longitude <= bbox['max_lon']):
            # Point is inside resort - distance is 0
            matching_resorts.append({
                'name': resort['name'],
                'distance_miles': 0.0,
                'country': resort.get('country', ''),
                'state': resort.get('state', '')
            })

    # If not inside any resort, find nearby ones
    if not matching_resorts:
        for resort in ski_resorts_data:
            bbox = resort.get('bbox', {})
            if not bbox:
                continue

            # Calculate distance to bbox center
            center_lat = (bbox['min_lat'] + bbox['max_lat']) / 2
            center_lon = (bbox['min_lon'] + bbox['max_lon']) / 2
            distance = haversine_distance_miles(latitude, longitude, center_lat, center_lon)

            if distance <= proximity_miles:
                matching_resorts.append({
                    'name': resort['name'],
                    'distance_miles': distance,
                    'country': resort.get('country', ''),
                    'state': resort.get('state', '')
                })

    # Sort by distance
    matching_resorts.sort(key=lambda x: x['distance_miles'])

    # Cache for 30 days
    _REVERSE_GEOCODING_CACHE.set(cache_key, matching_resorts, REVERSE_GEOCODING_CACHE_TTL)
    return matching_resorts
