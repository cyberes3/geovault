import time
import uuid
from typing import List, Tuple, Dict, NamedTuple, Union, Any

from django.db.models import QuerySet, Q
from django.http import JsonResponse

from api.models import FeatureStore, Collection
from api.views.collections.utils import get_collection_feature_ids
from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_required_setting

logger = get_tagged_logger()


class BboxQueryResult(NamedTuple):
    """Result of a bounding box query containing features and total count"""
    features: List[Dict]
    total_count: int
    fallback_used: bool = False  # Indicates if fallback mechanism was triggered


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
        return JsonResponse({
            'error': 'bbox parameter is required',
            'code': 400
        }, status=400)

    bbox = _parse_bbox(bbox_str)
    if not bbox:
        return JsonResponse({
            'error': 'Invalid bbox format. Expected: min_lon,min_lat,max_lon,max_lat',
            'code': 400
        }, status=400)

    # Validate and clamp zoom parameter
    try:
        zoom_level = int(zoom_str)
        # Clamp zoom level to valid range (1-20)
        if zoom_level < 1:
            zoom_level = 1
        elif zoom_level > 20:
            zoom_level = 20
    except ValueError:
        return JsonResponse({
            'error': 'Invalid zoom level. Expected integer between 1 and 20',
            'code': 400
        }, status=400)

    return bbox, zoom_level


def _build_bbox_response(features: List[Dict], total_count: int, zoom_level: int, fallback_used: bool, **extra_fields) -> Dict:
    """
    Build standardized bbox query response dictionary.
    
    Args:
        features: List of GeoJSON feature dictionaries
        total_count: Total number of features in bbox
        zoom_level: Zoom level used for query
        fallback_used: Whether fallback mechanism was used
        **extra_fields: Additional fields to include in response (e.g., 'tag' for public shares)
    
    Returns:
        Dictionary ready to be converted to JsonResponse
    """
    # Get the configured limit for comparison
    max_features = get_required_setting('MAX_FEATURES_PER_REQUEST')

    # Create GeoJSON FeatureCollection
    geojson_data = {
        "type": "FeatureCollection",
        "features": features
    }

    response_data = {
        'data': geojson_data,
        'feature_count': len(features),
        'total_features_in_bbox': total_count,
        'max_features_limit': max_features,
        'zoom_level': zoom_level,
        'timestamp': time.time(),
        'fallback_used': fallback_used
    }

    # Add any extra fields
    response_data.update(extra_fields)

    # Add warning if features were limited by configuration
    if 0 < max_features < total_count:
        response_data['warning'] = f'Displaying {len(features)} of {total_count} features due to MAX_FEATURES_PER_REQUEST limit ({max_features})'

    return response_data


def _build_collection_query(user_id: int, collection_id: uuid.UUID) -> QuerySet:
    """
    Build query for features in a collection.
    Returns features matching ANY of the collection's tags (OR logic) OR in feature_ids.
    
    Args:
        user_id: User ID to filter features by
        collection_id: Collection ID to filter features by
    
    Returns:
        QuerySet ready for further filtering
    """
    try:
        collection = Collection.objects.get(id=collection_id, user_id=user_id)
    except Collection.DoesNotExist:
        # Return empty queryset if collection doesn't exist
        return FeatureStore.objects.none()

    # Get feature IDs using the shared function to avoid code duplication
    feature_ids_set = get_collection_feature_ids(collection)

    # Start with base user filter
    base_query = FeatureStore.objects.filter(user_id=user_id).exclude(geometry__isnull=True)

    # Filter by the combined set of feature IDs
    if feature_ids_set:
        return base_query.filter(id__in=feature_ids_set).order_by('id')
    else:
        # No features match the collection criteria
        return FeatureStore.objects.none()


def _build_base_query(user_id: int, tag: str | None = None, collection_id: uuid.UUID | None = None) -> QuerySet:
    """
    Build base query for features with user filter, geometry exclusion, optional tag filter, 
    optional collection filter, and ordering.
    
    Args:
        user_id: User ID to filter features by
        tag: Optional tag to filter features by (if None, no tag filter is applied)
        collection_id: Optional collection ID to filter features by (if None, no collection filter is applied)
    
    Returns:
        QuerySet ready for further filtering
    """
    # Collection filter takes precedence if provided
    if collection_id is not None:
        return _build_collection_query(user_id, collection_id)

    base_query = FeatureStore.objects.filter(user_id=user_id).exclude(geometry__isnull=True)

    # Add tag filter if provided (search in both tags and system_tags)
    if tag:
        base_query = base_query.filter(
            Q(geojson__properties__tags__contains=[tag]) |
            Q(geojson__properties__system_tags__contains=[tag])
        )

    # Order by id to ensure consistent results when slicing
    return base_query.order_by('id')


def _convert_feature_to_geojson(feature: FeatureStore, public_safe: bool = False, include_tags: bool = False, allow_downloads: bool = False) -> dict[str, str | Any] | None:
    """
    Convert FeatureStore instance to GeoJSON Feature dictionary.
    
    Args:
        feature: FeatureStore instance
        public_safe: If True, excludes tags from properties unless include_tags is True (for public shares)
        include_tags: If True and public_safe=True, includes tags in properties (otherwise tags are excluded for public shares)
    
    Returns:
        GeoJSON Feature dictionary
    """
    geojson_data = feature.geojson
    if not geojson_data or 'geometry' not in geojson_data:
        return None

    # Create feature properties
    properties = geojson_data.get('properties', {}).copy()

    # Always include database_id for frontend processing
    properties['database_id'] = feature.id
    
    if public_safe:
        # Don't include tags in public view unless explicitly requested
        # (they can contain private information)
        if not include_tags and 'tags' in properties:
            del properties['tags']

    return {
        "type": "Feature",
        "geometry": geojson_data.get('geometry'),
        "properties": properties,
        "geojson_hash": feature.geojson_hash
    }
