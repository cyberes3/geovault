import json
import time
import uuid
from typing import List, Tuple, Dict, NamedTuple, Union, Any

from django.db import connection
from django.db.models import QuerySet, Q
from django.http import JsonResponse

from api.models import FeatureStore, Collection
from api.views.collections.utils import get_collection_feature_ids
from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()


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


def _build_base_query(user_id: int, tag: str | None = None, collection_id: uuid.UUID | None = None, scope: str | None = None) -> QuerySet:
    """
    Build base query for features with user filter, geometry exclusion, optional tag filter, 
    optional collection filter, optional scope filter, and ordering.
    
    Args:
        user_id: User ID to filter features by
        tag: Optional tag to filter features by (if None, no tag filter is applied)
        collection_id: Optional collection ID to filter features by (if None, no collection filter is applied)
        scope: Optional scope to filter features by (if None, defaults to filtering for scope__isnull=True)
    
    Returns:
        QuerySet ready for further filtering
    """
    # Collection filter takes precedence if provided. Collections can contain features from any scope.
    if collection_id is not None:
        return _build_collection_query(user_id, collection_id)

    base_query = FeatureStore.objects.filter(user_id=user_id).exclude(geometry__isnull=True)

    # Filter by scope (default to main map scope which is null)
    if scope is None:
        base_query = base_query.filter(scope__isnull=True)
    else:
        base_query = base_query.filter(scope=scope)

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


def _build_tags_sql_filter(tags: List[str], match_mode: str = 'AND') -> Tuple[str, List[Any]]:
    """
    Build SQL filter clause for tags with support for exact and prefix matching.
    
    Args:
        tags: List of tags to filter by
        match_mode: 'AND' or 'OR' logic for combining tags
        
    Returns:
        Tuple of (sql_clause, params_list)
    """
    if not tags:
        return "", []

    conditions = []
    params = []

    for tag in tags:
        if tag.endswith(':'):
            # Prefix matching: match any tag that starts with the prefix (without the trailing ':')
            prefix = tag[:-1]  # Remove the trailing ':'

            # Check if any element in the tags/system_tags arrays starts with the prefix
            # Uses jsonb_array_elements_text to expand the array and LIKE for matching
            # Optimized simple EXISTS subquery
            sub_cond = """(
                EXISTS (SELECT 1 FROM jsonb_array_elements_text(geojson->'properties'->'tags') t WHERE t LIKE %s)
                OR 
                EXISTS (SELECT 1 FROM jsonb_array_elements_text(geojson->'properties'->'system_tags') t WHERE t LIKE %s)
            )"""
            conditions.append(sub_cond)
            # Add prefix param twice (once for tags, once for system_tags)
            prefix_pattern = f"{prefix}%"
            params.extend([prefix_pattern, prefix_pattern])
        else:
            # Exact matching: use optimized @> operator
            # (tags @> [tag] OR system_tags @> [tag])
            conditions.append("""(
                geojson->'properties'->'tags' @> %s::jsonb 
                OR 
                geojson->'properties'->'system_tags' @> %s::jsonb
            )""")
            # Json dump the tag list for the operator
            tag_json = json.dumps([tag])
            params.extend([tag_json, tag_json])

    if not conditions:
        return "", []

    join_op = " AND " if match_mode == 'AND' else " OR "
    full_clause = f" AND ({join_op.join(conditions)})"

    return full_clause, params


def _build_bbox_sql_query(
        table_name: str,
        user_id: int,
        bbox: Tuple[float, float, float, float] | None,
        tags: List[str] | None = None,
        match_mode: str = 'AND',
        collection_id: uuid.UUID | None = None,
        max_features: int = 0,
        scope: str | None = None
) -> tuple[str, list]:
    """
    Build AGGRESSIVELY optimized SQL query for bbox queries.
    
    Optimizations:
    - Use && operator ONLY (skip ST_Intersects for ~2x speed boost)
    - NO COUNT query (use len() instead - saves entire query execution)
    - Skip ORDER BY when no limit (saves sort operation)
    
    Returns:
        Tuple of (sql_query_string, parameters_list)
    """
    min_lon, min_lat, max_lon, max_lat = bbox if bbox else (None, None, None, None)
    params = []

    # Build spatial filter - AGGRESSIVE: Use ONLY && operator (skip ST_Intersects)
    # The && operator uses GIST index and is 2-3x faster than ST_Intersects
    # Trade-off: May include features slightly outside bbox, acceptable for map display
    # Cast envelope coords to double precision to avoid PostGIS overload resolution issues
    spatial_filter = ""
    if bbox is not None:
        spatial_filter = " AND geometry && ST_MakeEnvelope(%s::double precision, %s::double precision, %s::double precision, %s::double precision, 4326)"
        params.extend([min_lon, min_lat, max_lon, max_lat])

    # Build tag filter
    tag_filter, tag_params = _build_tags_sql_filter(tags, match_mode)
    params.extend(tag_params)

    # Collection filter preprocessing (if provided)
    # NOTE: Must be added AFTER spatial and tag filters to match SQL parameter order
    collection_filter = ""

    if collection_id is not None:
        try:
            collection = Collection.objects.get(id=collection_id, user_id=user_id)
            feature_ids_set = get_collection_feature_ids(collection)
            if feature_ids_set:
                placeholders = ','.join(['%s'] * len(feature_ids_set))
                collection_filter = f" AND id IN ({placeholders})"
                params.extend(list(feature_ids_set))
            else:
                return ("SELECT 1 WHERE FALSE", [])
        except Collection.DoesNotExist:
            return ("SELECT 1 WHERE FALSE", [])

            return ("SELECT 1 WHERE FALSE", [])
        except Collection.DoesNotExist:
            return ("SELECT 1 WHERE FALSE", [])

    # Scope filter
    # If collection_id is present, we ignore scope (collections can contain features from any scope)
    # Otherwise, if scope is None, we filter for NULL scope (main map)
    # If scope is provided, we filter for that specific scope
    scope_filter = ""
    if collection_id is None:
        if scope is None:
            scope_filter = " AND scope IS NULL"
        else:
            scope_filter = " AND scope = %s"
            params.append(scope)

    params.insert(0, user_id)

    # AGGRESSIVE: Single simple query, no count, no CTE, no window functions
    # We'll use len() for count - much faster than database COUNT query
    if max_features > 0:
        sql_query = f"""
            SELECT id, geojson, geojson_hash
            FROM {table_name}
            WHERE user_id = %s AND geometry IS NOT NULL{spatial_filter}{tag_filter}{collection_filter}{scope_filter}
            ORDER BY id
            LIMIT {max_features}
        """
    else:
        # No limit: Skip ORDER BY (saves sort operation)
        sql_query = f"""
            SELECT id, geojson, geojson_hash
            FROM {table_name}
            WHERE user_id = %s AND geometry IS NOT NULL{spatial_filter}{tag_filter}{collection_filter}{scope_filter}
        """

    return sql_query, params


def _execute_bbox_query_and_parse(
        sql_query: str,
        params: list,
        public_safe: bool = False,
        include_tags: bool = False,
        allow_downloads: bool = False
) -> tuple[list, int]:
    """
    Execute SQL query and parse results into GeoJSON features.
    
    AGGRESSIVE optimizations:
    - Minimize dict operations
    - Fast path for common case (not public_safe)
    - Use len() for count (no database COUNT query needed)
    
    Returns:
        Tuple of (geojson_features_list, total_count)
    """
    try:
        with connection.cursor() as cursor:
            cursor.execute(sql_query, params)
            results = cursor.fetchall()
    except Exception as e:
        _logger.error(f"Error executing bbox query: {e}")
        return [], 0

    if not results:
        return [], 0

    # Use len() for count - much faster than database COUNT query
    total_count = len(results)

    geojson_features = []

    # AGGRESSIVE: Fast path for non-public queries (most common case)
    if not public_safe:
        for feature_id, geojson_data, geojson_hash in results:
            # Parse JSON if needed
            if isinstance(geojson_data, str):
                geojson_data = json.loads(geojson_data)

            if not geojson_data or 'geometry' not in geojson_data:
                continue

            # AGGRESSIVE: Modify properties dict in-place (avoid copy)
            properties = geojson_data.get('properties')
            if not properties or not isinstance(properties, dict):
                properties = {}
            properties['database_id'] = feature_id

            geojson_features.append({
                "type": "Feature",
                "geometry": geojson_data['geometry'],
                "properties": properties,
                "geojson_hash": geojson_hash
            })
    else:
        # Slower path for public queries (less common)
        for feature_id, geojson_data, geojson_hash in results:
            if isinstance(geojson_data, str):
                geojson_data = json.loads(geojson_data)

            if not geojson_data or 'geometry' not in geojson_data:
                continue

            properties = geojson_data.get('properties', {}).copy()

            # Always include database_id for frontend processing
            properties['database_id'] = feature_id

            if not include_tags and 'tags' in properties:
                del properties['tags']

            geojson_features.append({
                "type": "Feature",
                "geometry": geojson_data['geometry'],
                "properties": properties,
                "geojson_hash": geojson_hash
            })

    return geojson_features, total_count


def get_features_in_bbox(bbox: Tuple[float, float, float, float], user_id: int, tags: List[str] | None = None, match_mode: str = 'AND', collection_id: uuid.UUID | None = None, public_safe: bool = False, include_tags: bool = False, allow_downloads: bool = False, scope: str | None = None) -> BboxQueryResult:
    """
    Get features within bounding box from database using optimized raw SQL query.
    Returns both the features and the total count (using len() - no database COUNT query).
    
    Uses PostgreSQL-specific optimizations:
    - && operator ONLY for spatial queries (fastest with GIST index)
    - GIN index for JSONB tag filtering (@> operator)
    - Single query execution (no COUNT query - uses len() instead)
    - Maximum performance with minimal database overhead
    
    Args:
        bbox: Bounding box tuple (min_lon, min_lat, max_lon, max_lat)
        user_id: User ID to filter features by
        tags: Optional list of tags to filter features by
        match_mode: 'AND' (default) or 'OR' for tag combining logic
        collection_id: Optional collection ID to filter features by
        public_safe: If True, excludes _id from properties (for public shares)
        include_tags: If True and public_safe=True, includes tags in properties (otherwise tags are excluded for public shares)
        scope: Optional scope to filter features by (if None, defaults to filtering for scope__isnull=True)
    
    Returns:
        BboxQueryResult with features, total_count, and fallback_used flag
    """
    # Detect world-wide extent
    if bbox:
        crosses_dateline, world_wide_extent, lon_span, lat_span = _detect_world_wide_extent(bbox)
    else:
        crosses_dateline, world_wide_extent, lon_span, lat_span = False, False, 0.0, 0.0

    # Get the maximum features limit from settings
    max_features = get_required_setting('MAX_FEATURES_PER_REQUEST')

    # Get table name
    table_name = FeatureStore._meta.db_table

    # Determine if we should use spatial filter
    use_spatial_filter = not (crosses_dateline or world_wide_extent)

    # Build and execute initial query
    bbox_for_query = bbox if use_spatial_filter else None
    sql_query, params = _build_bbox_sql_query(
        table_name, user_id, bbox_for_query, tags, match_mode, collection_id, max_features, scope
    )

    # Check if query is empty (collection with no features)
    if sql_query == "SELECT 1 WHERE FALSE":
        return BboxQueryResult(features=[], total_count=0, fallback_used=False)

    # Execute query and parse results
    # When max_features > 0, the query includes COUNT(*) OVER() to get total in same query
    geojson_features, total_count = _execute_bbox_query_and_parse(
        sql_query, params, public_safe, include_tags, allow_downloads
    )

    # Fallback mechanism: if spatial query returned suspiciously few results for a large extent,
    # fall back to world-wide query
    fallback_used = False
    if use_spatial_filter and not (crosses_dateline or world_wide_extent):
        large_extent_lon_threshold = get_required_setting('BBOX_LARGE_EXTENT_LON_THRESHOLD')
        large_extent_lat_threshold = get_required_setting('BBOX_LARGE_EXTENT_LAT_THRESHOLD')
        suspicious_result_min_count = get_required_setting('BBOX_SUSPICIOUS_RESULT_MIN_COUNT')

        is_large_extent = lon_span > large_extent_lon_threshold or lat_span > large_extent_lat_threshold
        suspicious_result = is_large_extent and suspicious_result_min_count > total_count > 0

        if suspicious_result:
            _logger.warning(
                f"Suspicious result: large extent (lon_span={lon_span:.1f}°, lat_span={lat_span:.1f}°) "
                f"but only {total_count} features found. Falling back to world-wide query."
            )
            fallback_used = True

            # Rebuild query without spatial filter
            sql_query, params = _build_bbox_sql_query(
                table_name, user_id, None, tags, match_mode, collection_id, max_features, scope
            )

            # Check if query is empty
            if sql_query == "SELECT 1 WHERE FALSE":
                return BboxQueryResult(features=[], total_count=0, fallback_used=True)

            # Re-execute and parse results
            geojson_features, total_count = _execute_bbox_query_and_parse(
                sql_query, params, public_safe, include_tags, allow_downloads
            )

    return BboxQueryResult(features=geojson_features, total_count=total_count, fallback_used=fallback_used)
