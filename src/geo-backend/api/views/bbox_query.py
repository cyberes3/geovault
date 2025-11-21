import time
import traceback
import uuid
from typing import List, Tuple, Dict, NamedTuple, Union

from django.conf import settings
from django.contrib.gis.geos import Polygon
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from django.db.models import QuerySet, Q

from api.models import FeatureStore, Collection
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

    return (crosses_dateline, world_wide_extent, lon_span, lat_span)


def _validate_bbox_params(request) -> Union[Tuple[Tuple[float, ...], int], JsonResponse]:
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

    return (bbox, zoom_level)


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
    
    # Start with base user filter
    base_query = FeatureStore.objects.filter(user_id=user_id).exclude(geometry__isnull=True)
    
    # Build query for features matching collection criteria
    # Union of: features matching ANY collection tag OR features in feature_ids
    feature_ids_set = set()
    
    # 1. Get features matching ANY of the collection's tags (OR logic)
    if collection.tags:
        tag_query = Q()
        for tag in collection.tags:
            if tag:  # Only process non-empty tags
                tag_query |= Q(geojson__properties__tags__contains=[tag])
        
        if tag_query:
            tag_features = base_query.filter(tag_query).values_list('id', flat=True)
            feature_ids_set.update(tag_features)
    
    # 2. Add individually selected features
    if collection.feature_ids:
        # Verify these features belong to the user
        user_feature_ids = set(
            FeatureStore.objects.filter(user_id=user_id, id__in=collection.feature_ids)
            .values_list('id', flat=True)
        )
        feature_ids_set.update(user_feature_ids)
    
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
    
    # Add tag filter if provided
    if tag:
        base_query = base_query.filter(geojson__properties__tags__contains=[tag])
    
    # Order by id to ensure consistent results when slicing
    return base_query.order_by('id')


def _convert_feature_to_geojson(feature: FeatureStore, public_safe: bool = False, include_tags: bool = False) -> Dict:
    """
    Convert FeatureStore instance to GeoJSON Feature dictionary.
    
    Args:
        feature: FeatureStore instance
        public_safe: If True, excludes _id from properties (for public shares)
        include_tags: If True and public_safe=True, includes tags in properties (otherwise tags are excluded for public shares)
    
    Returns:
        GeoJSON Feature dictionary
    """
    geojson_data = feature.geojson
    if not geojson_data or 'geometry' not in geojson_data:
        return None

    # Create feature properties
    properties = geojson_data.get('properties', {}).copy()
    
    if public_safe:
        # Don't include database ID in public view
        if '_id' in properties:
            del properties['_id']
        # Don't include tags in public view unless explicitly requested
        # (they can contain private information)
        if not include_tags and 'tags' in properties:
            del properties['tags']
    else:
        # Include database ID in properties for frontend editing
        properties['_id'] = feature.id

    return {
        "type": "Feature",
        "geometry": geojson_data.get('geometry'),
        "properties": properties,
        "geojson_hash": feature.file_hash
    }


def _get_features_in_bbox(bbox: Tuple[float, float, float, float], user_id: int, zoom_level: int, tag: str | None = None, collection_id: uuid.UUID | None = None, public_safe: bool = False, include_tags: bool = False) -> BboxQueryResult:
    """
    Get features within bounding box from database, handling world-wide extents that cross the International Date Line.
    Returns both the features and the total count in a single optimized operation.
    
    Args:
        bbox: Bounding box tuple (min_lon, min_lat, max_lon, max_lat)
        user_id: User ID to filter features by
        zoom_level: Zoom level (used for logging/optimization)
        tag: Optional tag to filter features by
        collection_id: Optional collection ID to filter features by
        public_safe: If True, excludes _id from properties (for public shares)
        include_tags: If True and public_safe=True, includes tags in properties (otherwise tags are excluded for public shares)
    
    Returns:
        BboxQueryResult with features, total_count, and fallback_used flag
    """
    # Detect world-wide extent
    crosses_dateline, world_wide_extent, lon_span, lat_span = _detect_world_wide_extent(bbox)

    # Get the maximum features limit from settings
    max_features = getattr(settings, 'MAX_FEATURES_PER_REQUEST', -1)

    # Build base query with user filter, optional tag/collection filter, and ordering
    base_query_filter = _build_base_query(user_id, tag, collection_id)

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
        geojson_feature = _convert_feature_to_geojson(feature, public_safe, include_tags)
        if geojson_feature:
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
                geojson_feature = _convert_feature_to_geojson(feature, public_safe, include_tags)
                if geojson_feature:
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
    - collection: optional collection ID to filter features by
    """
    # Validate bbox and zoom parameters
    validation_result = _validate_bbox_params(request)
    if isinstance(validation_result, JsonResponse):
        return validation_result
    bbox, zoom_level = validation_result

    # Get optional collection parameter
    collection_id = None
    collection_str = request.GET.get('collection')
    if collection_str:
        try:
            collection_id = uuid.UUID(collection_str)
            # Verify collection belongs to user
            try:
                Collection.objects.get(id=collection_id, user=request.user)
            except Collection.DoesNotExist:
                return JsonResponse({
                    'success': False,
                    'error': 'Collection not found',
                    'code': 404
                }, status=404)
        except (ValueError, TypeError):
            return JsonResponse({
                'success': False,
                'error': 'Invalid collection ID. Expected UUID',
                'code': 400
            }, status=400)

    # Fetch data from database with optimized single query
    try:
        query_result = _get_features_in_bbox(bbox, request.user.id, zoom_level, collection_id=collection_id)
        features = query_result.features
        total_features_in_bbox = query_result.total_count
        fallback_used = query_result.fallback_used

        # Build response using helper function
        response_data = _build_bbox_response(features, total_features_in_bbox, zoom_level, fallback_used)

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error in get_geojson_data API: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get features in bounding box',
            'code': 500
        }, status=500)
