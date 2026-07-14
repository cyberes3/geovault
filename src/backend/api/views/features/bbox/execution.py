"""Bbox SQL query execution, result parsing, and the world-wide fallback mechanism."""
import json
import uuid
from typing import Dict, List, NamedTuple, Tuple

from django.db import connection

from api.models import FeatureStore
from api.views.features.bbox.params import _detect_world_wide_extent
from api.views.features.bbox.sql_builder import _build_bbox_sql_query
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.tagging.const_strings import strip_private_tags
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()


class BboxQueryResult(NamedTuple):
    """Result of a bounding box query containing features and total count"""
    features: List[Dict]
    total_count: int
    fallback_used: bool = False  # Indicates if fallback mechanism was triggered


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

            if not include_tags:
                strip_private_tags(properties)

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
