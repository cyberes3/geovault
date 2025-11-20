import time
import traceback
from typing import List, Tuple, Dict, NamedTuple

from django.conf import settings
from django.contrib.gis.geos import Polygon
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


class BboxQueryResult(NamedTuple):
    """Result of a bounding box query containing features and total count"""
    features: List[Dict]
    total_count: int
    fallback_used: bool = False  # Indicates if fallback mechanism was triggered


def _parse_bbox(bbox_str: str) -> tuple[float, ...] | None:
    """Parse bounding box string into tuple of floats"""
    try:
        parts = bbox_str.split(',')
        if len(parts) != 4:
            return None
        return tuple(float(x.strip()) for x in parts)
    except (ValueError, AttributeError):
        return None


def _get_features_in_bbox(bbox: Tuple[float, float, float, float], user_id: int, zoom_level: int) -> BboxQueryResult:
    """
    Get features within bounding box from database, handling world-wide extents that cross the International Date Line.
    Returns both the features and the total count in a single optimized operation.
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
    world_wide_extent = False
    if crosses_dateline:
        world_wide_extent = True
    else:
        # Check longitude span (more conservative: 280° instead of 300°)
        if lon_span > 280:
            world_wide_extent = True
        # Check latitude span (if lat span > 170°, treat as world-wide)
        elif lat_span > 170:
            world_wide_extent = True
        # Additional check for very large extents (>270° longitude)
        elif lon_span > 270:
            world_wide_extent = True

    # Get the maximum features limit from settings
    max_features = getattr(settings, 'MAX_FEATURES_PER_REQUEST', -1)

    # Base query with explicit NULL geometry exclusion and ordering
    # Exclude features with NULL geometry to ensure consistent query behavior
    # Order by id to ensure consistent results when slicing
    base_query_filter = FeatureStore.objects.filter(user_id=user_id).exclude(geometry__isnull=True).order_by('id')

    if crosses_dateline or world_wide_extent:
        # Handle world-wide bbox that crosses the International Date Line or spans most of the globe
        base_query = base_query_filter
    else:
        # Normal bbox that doesn't cross the International Date Line
        # Use spatial query with error handling
        try:
            bbox_polygon = Polygon.from_bbox(bbox)
            base_query = base_query_filter.filter(geometry__intersects=bbox_polygon)
        except Exception as e:
            logger.warning(f"Error creating bbox polygon or spatial query: {e}. Falling back to world-wide query.")
            # Fallback to world-wide query if spatial query fails
            base_query = base_query_filter

    # Get total count first (this is a lightweight operation)
    total_count = base_query.count()

    # Apply limit if configured (max_features = -1 means no limit)
    if max_features > 0:
        features_query = base_query[:max_features]
    else:
        features_query = base_query

    # Convert to GeoJSON format
    geojson_features = []
    for feature in features_query:
        geojson_data = feature.geojson
        if geojson_data and 'geometry' in geojson_data:
            # Wrap the data in a proper GeoJSON Feature structure
            # Include database ID in properties for frontend editing
            properties = geojson_data.get('properties', {}).copy()
            properties['_id'] = feature.id  # Add database ID for editing
            geojson_feature = {
                "type": "Feature",
                "geometry": geojson_data.get('geometry'),
                "properties": properties,
                "geojson_hash": feature.file_hash
            }
            geojson_features.append(geojson_feature)

    # Fallback mechanism: if spatial query returned suspiciously few results for a large extent,
    # fall back to world-wide query
    fallback_used = False
    if not (crosses_dateline or world_wide_extent):
        # If we used a spatial query but got very few results for a large extent, something might be wrong
        # Check if the extent is large (>200° longitude or >150° latitude) but we got very few results
        is_large_extent = lon_span > 200 or lat_span > 150
        suspicious_result = is_large_extent and total_count < 10 and total_count > 0

        if suspicious_result:
            logger.warning(
                f"Suspicious result: large extent (lon_span={lon_span:.1f}°, lat_span={lat_span:.1f}°) "
                f"but only {total_count} features found. Falling back to world-wide query."
            )
            fallback_used = True
            # Fall back to world-wide query
            base_query = base_query_filter
            total_count = base_query.count()

            # Re-apply limit if configured
            if max_features > 0:
                features_query = base_query[:max_features]
            else:
                features_query = base_query

            # Re-convert to GeoJSON format
            geojson_features = []
            for feature in features_query:
                geojson_data = feature.geojson
                if geojson_data and 'geometry' in geojson_data:
                    # Include database ID in properties for frontend editing
                    properties = geojson_data.get('properties', {}).copy()
                    properties['_id'] = feature.id  # Add database ID for editing
                    geojson_feature = {
                        "type": "Feature",
                        "geometry": geojson_data.get('geometry'),
                        "properties": properties,
                        "geojson_hash": feature.file_hash
                    }
                    geojson_features.append(geojson_feature)

    return BboxQueryResult(features=geojson_features, total_count=total_count, fallback_used=fallback_used)


@login_required_401
@require_http_methods(["GET"])
def get_geojson_data(request):
    """
    API endpoint to fetch GeoJSON data for a given bounding box.

    Query parameters:
    - bbox: comma-separated bounding box (min_lon,min_lat,max_lon,max_lat)
    - zoom: zoom level (integer, 1-20)
    """
    # Get query parameters
    bbox_str = request.GET.get('bbox')
    zoom_str = request.GET.get('zoom', '10')

    # Validate bbox parameter
    if not bbox_str:
        return JsonResponse({
            'success': False,
            'error': 'bbox parameter is required',
            'code': 400
        }, status=400)

    bbox = _parse_bbox(bbox_str)
    if not bbox:
        return JsonResponse({
            'success': False,
            'error': 'Invalid bbox format. Expected: min_lon,min_lat,max_lon,max_lat',
            'code': 400
        }, status=400)

    # Validate zoom parameter
    try:
        zoom_level = int(zoom_str)
        if zoom_level < 1 or zoom_level > 20:
            raise ValueError("Zoom level out of range")
    except ValueError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid zoom level. Expected integer between 1 and 20',
            'code': 400
        }, status=400)

    # Fetch data from database with optimized single query
    try:
        query_result = _get_features_in_bbox(bbox, request.user.id, zoom_level)
        features = query_result.features
        total_features_in_bbox = query_result.total_count
        fallback_used = query_result.fallback_used

        # Get the configured limit for comparison
        max_features = getattr(settings, 'MAX_FEATURES_PER_REQUEST', -1)

        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": features
        }

        response_data = {
            'success': True,
            'data': geojson_data,
            'feature_count': len(features),
            'total_features_in_bbox': total_features_in_bbox,
            'max_features_limit': max_features,
            'zoom_level': zoom_level,
            'timestamp': time.time(),
            'fallback_used': fallback_used
        }

        # Add warning if features were limited by configuration
        if 0 < max_features < total_features_in_bbox:
            response_data['warning'] = f'Displaying {len(features)} of {total_features_in_bbox} features due to MAX_FEATURES_PER_REQUEST limit ({max_features})'

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error in get_geojson_data API: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get features in bounding box',
            'code': 500
        }, status=500)
