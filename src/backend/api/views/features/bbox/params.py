"""Bounding-box and zoom query-parameter parsing/validation."""
from typing import Tuple, Union

from django.http import JsonResponse

from api.utils.responses import error_response
from website.settings_utils import get_required_setting


def _parse_bbox(bbox_str: str) -> tuple[float, float, float, float] | None:
    """Parse bounding box string into tuple of floats"""
    try:
        parts = bbox_str.split(',')
        if len(parts) != 4:
            return None
        parsed = tuple(float(x.strip()) for x in parts)
        return parsed[0], parsed[1], parsed[2], parsed[3]
    except (ValueError, AttributeError):
        return None


def _detect_world_wide_extent(bbox: Tuple[float, float, float, float]) -> Tuple[bool, bool, float, float]:
    """
    Detect if a bounding box represents a world-wide extent.

    Returns:
        Tuple of (crosses_dateline, world_wide_extent, lon_span, lat_span)
    """
    min_lon, min_lat, max_lon, max_lat = bbox

    # Calculate spans for world-wide detection
    lon_span = max_lon - min_lon if max_lon >= min_lon else (180 - min_lon) + (max_lon + 180)
    lat_span = max_lat - min_lat

    # Check if this is a world-wide bbox that crosses the International Date Line
    # This happens when min_lon > max_lon (e.g., min_lon=134, max_lon=134 means we're crossing 180°/-180°)
    crosses_dateline = min_lon > max_lon

    # Improved world-wide extent detection with more conservative thresholds
    # Lower threshold from 300° to 280° for more conservative detection
    # Also check latitude span (>170° indicates world-wide view)
    world_wide_lon_threshold_1 = get_required_setting('BBOX_WORLD_WIDE_LON_THRESHOLD_1')
    world_wide_lon_threshold_2 = get_required_setting('BBOX_WORLD_WIDE_LON_THRESHOLD_2')
    world_wide_lat_threshold = get_required_setting('BBOX_WORLD_WIDE_LAT_THRESHOLD')

    world_wide_extent = False
    if crosses_dateline:
        world_wide_extent = True
    else:
        # Check longitude span (more conservative: 280° instead of 300°)
        if lon_span > world_wide_lon_threshold_1:
            world_wide_extent = True
        # Check latitude span (if lat span > 170°, treat as world-wide)
        elif lat_span > world_wide_lat_threshold:
            world_wide_extent = True
        # Additional check for very large extents (>270° longitude)
        elif lon_span > world_wide_lon_threshold_2:
            world_wide_extent = True

    return crosses_dateline, world_wide_extent, lon_span, lat_span


def _validate_bbox_params(request) -> Union[Tuple[Tuple[float, float, float, float], int], JsonResponse]:
    """
    Validate bbox and zoom parameters from request.

    Returns:
        Tuple of (bbox, zoom_level) on success, or JsonResponse with error on failure
    """
    # Get query parameters
    bbox_str = request.GET.get('bbox')
    zoom_str = request.GET.get('zoom', '10')

    # Validate bbox parameter
    if not bbox_str:
        return error_response('bbox parameter is required', code=400)

    bbox = _parse_bbox(bbox_str)
    if not bbox:
        return error_response('Invalid bbox format. Expected: min_lon,min_lat,max_lon,max_lat', code=400)

    # Validate and clamp zoom parameter
    try:
        zoom_level = int(zoom_str)
        # Clamp zoom level to valid range (1-20)
        if zoom_level < 1:
            zoom_level = 1
        elif zoom_level > 20:
            zoom_level = 20
    except ValueError:
        return error_response('Invalid zoom level. Expected integer between 1 and 20', code=400)

    return bbox, zoom_level
