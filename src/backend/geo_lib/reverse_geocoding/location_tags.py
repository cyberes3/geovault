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

from geo_lib.reverse_geocoding.areas_server_client import query_areas_server
from geo_lib.reverse_geocoding.protected_areas import classify_protected_area
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
        # Single source: areas server (admin, protected areas, nearby lakes, ocean, ski_resort; city filled from place nodes when admin has none)
        admin_hierarchy, protected_areas, nearby_lakes, ocean, ski_resort, areas_err = query_areas_server(latitude, longitude)
        if areas_err:
            _logger.error(areas_err)
            log_messages.append(ReverseGeocodingLogMessage(
                timestamp=datetime.now(),
                message=areas_err,
                level='ERROR',
                source='Reverse Geocoding',
            ))
            admin_info = {'country': None, 'state': None, 'county': None, 'city': None}
            protected_areas = []
            nearby_lakes = []
        else:
            admin_info = admin_hierarchy

        has_any_data = (
            admin_info.get('country') or admin_info.get('state') or
            admin_info.get('county') or admin_info.get('city') or
            protected_areas or nearby_lakes
        )
        if not has_any_data and not areas_err:
            warning_msg = (
                f"Reverse geocoding returned no data for coordinates "
                f"({latitude}, {longitude}) - no matching features found"
            )
            _logger.info(warning_msg)

        # Add administrative tags
        if admin_info['country']:
            tags.append(f"country:{admin_info['country']}")
        if admin_info['state']:
            tags.append(f"state:{admin_info['state']}")
        if admin_info['county']:
            tags.append(f"county:{admin_info['county']}")
        if admin_info['city']:
            tags.append(f"city:{admin_info['city']}")

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

        # Ocean tags from areas server (up to 2: sub-region then main ocean)
        ocean_list = ocean if isinstance(ocean, list) else ([ocean] if ocean and isinstance(ocean, str) else [])
        seen_ocean = set()
        for name in ocean_list[:2]:
            if not name or not isinstance(name, str):
                continue
            n = name.strip()
            if n and n not in seen_ocean:
                seen_ocean.add(n)
                tags.append(f"ocean:{n}")

        # Add lake tags
        lake_tags = set()
        for lake in nearby_lakes[:3]:  # Limit to 3 closest lakes
            lake_tags.add(f"lake:{lake['name']}")
        tags.extend(sorted(lake_tags))

        # Ski resort from areas server (single tag when point is inside a resort)
        if ski_resort and isinstance(ski_resort, str) and ski_resort.strip():
            tags.append(f"ski-resort:{ski_resort.strip()}")

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
    Internal helper: Fetch tags from API (caching happens at API level).
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        
    Returns:
        Tuple of (tags list, log messages list)
    """
    # Fetch from API (caching is handled at the areas server level)
    tags, log_messages = get_location_tags(latitude, longitude)

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
    - Processes multiple coordinates in parallel for better performance
    
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

    # Step 2: Fetch results for unique coordinates in parallel (cache-aware)
    results = {}
    unique_coords = list(coord_mapping.keys())
    
    # Process coordinates in parallel to avoid sequential delays
    # Limit to 2 concurrent workers to avoid overwhelming the API
    with ThreadPoolExecutor(max_workers=min(len(unique_coords), 1)) as executor:
        future_to_coord = {
            executor.submit(_get_from_cache_or_fetch, lat, lon): (lat, lon)
            for lat, lon in unique_coords
        }
        
        for future in future_to_coord:
            lat, lon = future_to_coord[future]
            try:
                tags, log_messages = future.result()
                results[(lat, lon)] = (tags, log_messages)
            except Exception as e:
                _logger.error(f"Error reverse geocoding ({lat}, {lon}): {e}")
                results[(lat, lon)] = ([], [])

    # Step 3: Map all original coordinates back to results
    final_results = {}
    for rounded_coord, original_coords in coord_mapping.items():
        result = results.get(rounded_coord, ([], []))
        for original_coord in original_coords:
            final_results[original_coord] = result

    return final_results
