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
from typing import Dict, List, Optional, Tuple, Union

from geo_lib.reverse_geocoding.areas_server_client import query_areas_server
from geo_lib.reverse_geocoding.areas_server_models import AreasQueryResponse
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


def tags_from_areas_data(response: Union[AreasQueryResponse, dict]) -> List[str]:
    """
    Build tag list from areas server response (pure function, no I/O).
    response: AreasQueryResponse or legacy dict with admin_hierarchy, protected_areas, etc.
    """
    if isinstance(response, dict):
        response = AreasQueryResponse.model_validate(response)

    tags: List[str] = []
    ah = response.admin_hierarchy
    if ah.country:
        tags.append(f"country:{ah.country}")
    if ah.state:
        tags.append(f"state:{ah.state}")
    if ah.county:
        tags.append(f"county:{ah.county}")
    if ah.city:
        tags.append(f"city:{ah.city}")

    protected_area_tags: set = set()
    for area in response.protected_areas:
        if not area.name:
            continue
        area_type = classify_protected_area(area.model_dump())
        protected_area_tags.add(f"{area_type}:{area.name}")
    tags.extend(sorted(protected_area_tags))

    seen_ocean: set = set()
    for name in response.ocean[:2]:
        n = (name or "").strip()
        if n and n not in seen_ocean:
            seen_ocean.add(n)
            tags.append(f"ocean:{n}")

    lake_tags: set = set()
    for lake in response.nearby_lakes[:3]:
        lake_tags.add(f"lake:{lake.name}")
    tags.extend(sorted(lake_tags))

    if response.ski_resort and response.ski_resort.strip():
        tags.append(f"ski-resort:{response.ski_resort.strip()}")

    return tags


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
        response, areas_err = query_areas_server(latitude, longitude)
        if areas_err:
            _logger.error(areas_err)
            log_messages.append(ReverseGeocodingLogMessage(
                timestamp=datetime.now(),
                message=areas_err,
                level='ERROR',
                source='Reverse Geocoding',
            ))
            response = AreasQueryResponse.empty()

        has_any_data = response.has_any_location_data()
        if not has_any_data and not areas_err:
            warning_msg = (
                f"Reverse geocoding returned no data for coordinates "
                f"({latitude}, {longitude}) - no matching features found"
            )
            _logger.info(warning_msg)

        tags = tags_from_areas_data(response)
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
