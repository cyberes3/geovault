"""
Main public API for generating location tags from coordinates.

This module provides the primary entry point for reverse geocoding:
- batch_reverse_geocode_coordinates(): Batch process multiple coordinates (RECOMMENDED)
- reverse_geocode_coordinates(): Generate tags for a single coordinate (GET)

Tags are generated from multiple sources:
- Administrative boundaries (city, state, country)
- Protected areas (national parks, state parks, etc.)
- Lakes and water bodies
- Nearby ski resorts
"""
from dataclasses import dataclass
from datetime import datetime
from typing import Dict, List, Tuple, Union

from website.settings_utils import get_setting

from geo_lib.reverse_geocoding.areas_server_client import query_areas_server, query_areas_server_batch
from geo_lib.reverse_geocoding.areas_server_models import AreasQueryResponse
from geo_lib.reverse_geocoding.constants import WATERWAY_TAG_MAX_DISTANCE_M
from geo_lib.reverse_geocoding.protected_areas import classify_protected_area
from geo_lib.logging.console import get_tagged_logger
from geo_lib.spatial.coordinates import round_coordinate

_logger = get_tagged_logger()

# Normalize Unicode en-dash/em-dash to ASCII hyphen in tag values.
_UNICODE_EN_DASH = "\u2013"
_UNICODE_EM_DASH = "\u2014"


def _normalize_name_for_tag(name: str) -> str:
    """Replace underscores with spaces and Unicode dashes with ASCII hyphen."""
    s = (name or "").strip().replace("_", " ")
    return s.replace(_UNICODE_EN_DASH, "-").replace(_UNICODE_EM_DASH, "-")


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
    response: AreasQueryResponse, or a dict with the same fields (validated into the model).
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
        name_for_tag = _normalize_name_for_tag(area.name)
        protected_area_tags.add(f"{area_type}:{name_for_tag}")
    tags.extend(sorted(protected_area_tags))

    seen_ocean: set = set()
    for name in response.ocean[:2]:
        n = _normalize_name_for_tag(name or "")
        if n and n not in seen_ocean:
            seen_ocean.add(n)
            tags.append(f"ocean:{n}")

    lake_tags: set = set()
    for lake in response.lakes[:3]:
        lake_tags.add(f"lake:{_normalize_name_for_tag(lake.name)}")
    tags.extend(sorted(lake_tags))

    if response.ski_resort and response.ski_resort.strip():
        tags.append(f"ski-resort:{_normalize_name_for_tag(response.ski_resort)}")

    # Add waterway tag when within 300 ft of river/canal centerline (rivers are linestrings, not polygons like lakes).
    if response.waterway and response.waterway.name and response.waterway.name.strip():
        dist_m = response.waterway.distance_m
        if dist_m is not None and dist_m <= WATERWAY_TAG_MAX_DISTANCE_M:
            tags.append(f"waterway:{_normalize_name_for_tag(response.waterway.name)}")

    return tags


def reverse_geocode_coordinates(
    latitude: float,
    longitude: float,
) -> Tuple[List[str], List[ReverseGeocodingLogMessage]]:
    """
    Generate comprehensive location tags for a single coordinate (GET /query).

    For batch operations, use batch_reverse_geocode_coordinates() instead for better
    performance through POST batch and deduplication.

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


def batch_reverse_geocode_coordinates(
    coordinates: List[Tuple[float, float]],
) -> Dict[Tuple[float, float], Tuple[List[str], List[ReverseGeocodingLogMessage]]]:
    """
    Batch reverse geocode multiple coordinates via POST /query (deduplicated, chunked).

    Features:
    - Deduplicates nearby coordinates (rounded to ~111m precision)
    - Chunks by AREAS_SERVER_MAX_BATCH_SIZE and uses POST /query per chunk
    - Maps results back to every input coordinate

    Args:
        coordinates: List of (latitude, longitude) tuples

    Returns:
        Dict mapping each input coordinate to (tags, log_messages) tuple
    """
    if not coordinates:
        return {}

    # Step 1: Deduplicate by rounded coordinate
    coord_mapping: Dict[Tuple[float, float], List[Tuple[float, float]]] = {}
    for lat, lon in coordinates:
        exact = (lat, lon)
        rounded = round_coordinate(lat, lon)
        if rounded not in coord_mapping:
            coord_mapping[rounded] = []
        coord_mapping[rounded].append(exact)

    unique_coords = list(coord_mapping.keys())
    max_batch = get_setting("AREAS_SERVER_MAX_BATCH_SIZE", 100)
    results: Dict[Tuple[float, float], Tuple[List[str], List[ReverseGeocodingLogMessage]]] = {}

    # Step 2: Chunk and call POST batch per chunk
    for start in range(0, len(unique_coords), max_batch):
        chunk = unique_coords[start : start + max_batch]
        responses, err = query_areas_server_batch(chunk)
        if err:
            error_log = ReverseGeocodingLogMessage(
                timestamp=datetime.now(),
                message=err,
                level="ERROR",
                source="Reverse Geocoding",
            )
            for r_lat, r_lon in chunk:
                results[(r_lat, r_lon)] = ([], [error_log])
            continue
        assert responses is not None
        for i, (r_lat, r_lon) in enumerate(chunk):
            resp = responses[i]
            tags = tags_from_areas_data(resp)
            log_messages: List[ReverseGeocodingLogMessage] = []
            if not resp.has_any_location_data():
                log_messages.append(
                    ReverseGeocodingLogMessage(
                        timestamp=datetime.now(),
                        message=f"Reverse geocoding returned no data for ({r_lat}, {r_lon})",
                        level="INFO",
                        source="Reverse Geocoding",
                    )
                )
            results[(r_lat, r_lon)] = (tags, log_messages)

    # Step 3: Map all original coordinates back to results
    final_results: Dict[Tuple[float, float], Tuple[List[str], List[ReverseGeocodingLogMessage]]] = {}
    for rounded_coord, original_coords in coord_mapping.items():
        result = results.get(rounded_coord, ([], []))
        for original_coord in original_coords:
            final_results[original_coord] = result

    return final_results
