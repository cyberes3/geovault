"""
Main public API for generating location tags from coordinates.

This module provides the primary entry point for reverse geocoding:
- batch_geocode_coordinates(): Batch process multiple coordinates (RECOMMENDED)
- get_location_tags(): Generate tags for a single coordinate

Tags are generated from multiple sources:
- Administrative boundaries (city, state, country)
- Protected areas (national parks, state parks, etc.)
- Nearby lakes and water bodies
- Nearby ski resorts
"""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime
from typing import List, Tuple, Dict

from geo_lib.geocoding.admin_boundaries import get_admin_hierarchy
from geo_lib.geocoding.cache import _REVERSE_GEOCODING_CACHE, _get_cache_key
from geo_lib.geocoding.constants import REVERSE_GEOCODING_CACHE_TTL
from geo_lib.geocoding.nearby_places import find_nearby_cities, search_nearby_lakes
from geo_lib.geocoding.protected_areas import get_protected_areas, classify_protected_area
from geo_lib.geocoding.ski_resorts import search_nearby_ski_resorts
from geo_lib.logging.console import get_tagged_logger
from geo_lib.spatial.coordinates import round_coordinate

_logger = get_tagged_logger()


@dataclass
class ReverseGeocodingLogMessage:
    """Log message from reverse geocoding operations."""
    timestamp: datetime
    message: str
    level: str  # 'INFO', 'WARNING', 'ERROR'
    source: str  # 'Reverse Geocoding'


def get_location_tags(
    latitude: float,
    longitude: float
) -> Tuple[List[str], List[ReverseGeocodingLogMessage]]:
    """
    Generate comprehensive location tags for a coordinate.
    
    This function queries multiple data sources in parallel to generate
    location tags in format: city:Name, state:Name, country:Name, etc.
    
    For batch operations, use batch_geocode_coordinates() instead for better
    performance through automatic deduplication.
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
    
    Returns:
        Tuple of (tags list, log messages list)
    """
    tags = []
    log_messages = []

    try:
        # Run independent queries in parallel for better performance
        with ThreadPoolExecutor(max_workers=3) as executor:
            admin_future = executor.submit(get_admin_hierarchy, latitude, longitude)
            protected_future = executor.submit(get_protected_areas, latitude, longitude)
            lakes_future = executor.submit(search_nearby_lakes, latitude, longitude)

            # Wait for results
            admin_info = admin_future.result()
            protected_areas = protected_future.result()
            nearby_lakes = lakes_future.result()

        # Check if we got any location data at all (indicates API failures)
        has_any_data = (
            admin_info.get('country') or admin_info.get('state') or
            admin_info.get('county') or admin_info.get('city') or
            protected_areas or nearby_lakes
        )

        if not has_any_data:
            # No data returned from any query - likely API failures
            error_msg = (
                f"Reverse geocoding returned no data for coordinates "
                f"({latitude}, {longitude}) - possible API failures (check console logs)"
            )
            _logger.warning(error_msg)
            log_messages.append(ReverseGeocodingLogMessage(
                timestamp=datetime.now(),
                message=error_msg,
                level='WARNING',
                source='Reverse Geocoding'
            ))

        # Add administrative tags
        if admin_info['country']:
            tags.append(f"country:{admin_info['country']}")
        if admin_info['state']:
            tags.append(f"state:{admin_info['state']}")
        if admin_info['county']:
            tags.append(f"county:{admin_info['county']}")

        city_found = False
        if admin_info['city']:
            tags.append(f"city:{admin_info['city']}")
            city_found = True

        # If no city found in admin boundaries, search for nearby cities
        if not city_found:
            nearby_cities = find_nearby_cities(latitude, longitude)
            if nearby_cities:
                # Use closest city
                closest_city = nearby_cities[0]
                tags.append(f"city:{closest_city['name']}")
                city_found = True

        # Process protected areas
        protected_area_tags = set()  # Use set to prevent duplicates

        for area in protected_areas:
            name = area.get('name')
            if not name:
                continue

            # Classify the area and create appropriate tag
            area_type = classify_protected_area(area)
            protected_area_tags.add(f"{area_type}:{name}")

        # Add protected area tags (sorted for consistency)
        tags.extend(sorted(protected_area_tags))

        # Add lake tags
        lake_tags = set()
        for lake in nearby_lakes[:3]:  # Limit to 3 closest lakes
            lake_tags.add(f"lake:{lake['name']}")
        tags.extend(sorted(lake_tags))

        # Search for nearby ski resorts (within 1 mile)
        # Note: Ski resort detection is limited in OSM - many resorts lack proper tagging
        nearby_ski_resorts = search_nearby_ski_resorts(latitude, longitude, 1.0)
        ski_tags = set()
        for resort in nearby_ski_resorts[:2]:  # Limit to 2 closest resorts
            ski_tags.add(f"ski-resort:{resort['name']}")
        tags.extend(sorted(ski_tags))

        return tags, log_messages

    except Exception as e:
        error_msg = f"Error generating location tags for ({latitude}, {longitude}): {e}"
        _logger.error(error_msg)
        log_messages.append(ReverseGeocodingLogMessage(
            timestamp=datetime.now(),
            message=error_msg,
            level='ERROR',
            source='Reverse Geocoding'
        ))
        return [], log_messages


def _get_from_cache_or_fetch(
    latitude: float,
    longitude: float
) -> Tuple[List[str], List[ReverseGeocodingLogMessage]]:
    """
    Internal helper: Check cache first, fetch from API if needed.
    
    This is the only place that checks/sets the top-level tag cache.
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        
    Returns:
        Tuple of (tags list, log messages list)
    """
    # Check top-level cache for complete tag results
    cache_key = _get_cache_key(latitude, longitude, prefix="reverse_geocode:tags")
    cached = _REVERSE_GEOCODING_CACHE.get(cache_key)

    if cached is not None:
        # Return cached tags and empty log messages (already processed)
        return cached, []

    # Not in cache - fetch from API
    tags, log_messages = get_location_tags(latitude, longitude)

    # Cache the results for 30 days
    _REVERSE_GEOCODING_CACHE.set(cache_key, tags, REVERSE_GEOCODING_CACHE_TTL)

    return tags, log_messages


def batch_reverse_geocode_coordinates(
    coordinates: List[Tuple[float, float]]
) -> Dict[Tuple[float, float], Tuple[List[str], List[ReverseGeocodingLogMessage]]]:
    """
    THE MAIN ENTRY POINT: Batch reverse geocode multiple coordinates with deduplication.
    
    This is the primary function that should be called for optimal performance.
    
    Features:
    - Deduplicates nearby coordinates (rounded to ~111m precision)
    - Leverages multi-level caching (per-coordinate and top-level tag cache)
    - Minimizes API calls by batching and cache checking
    - Thread-safe coordinate deduplication
    
    Args:
        coordinates: List of (latitude, longitude) tuples
        
    Returns:
        Dict mapping each input coordinate to (tags, log_messages) tuple
    """
    if not coordinates:
        return {}

    # Step 1: Deduplicate coordinates by rounding to cache precision
    coord_mapping = {}  # Maps rounded coord -> list of original coords
    for lat, lon in coordinates:
        rounded = round_coordinate(lat, lon)
        if rounded not in coord_mapping:
            coord_mapping[rounded] = []
        coord_mapping[rounded].append((lat, lon))

    # Step 2: Fetch results for unique coordinates (cache-aware)
    results = {}
    for rounded_coord in coord_mapping.keys():
        lat, lon = rounded_coord
        tags, log_messages = _get_from_cache_or_fetch(lat, lon)
        results[rounded_coord] = (tags, log_messages)

    # Step 3: Map all original coordinates back to results
    final_results = {}
    for rounded_coord, original_coords in coord_mapping.items():
        result = results[rounded_coord]
        for original_coord in original_coords:
            final_results[original_coord] = result

    return final_results
