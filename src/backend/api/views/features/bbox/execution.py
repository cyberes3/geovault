"""Bbox SQL query execution and result parsing."""
import copy
import json
import uuid
from typing import Dict, List, NamedTuple, Tuple

from django.db import connection

from api.models import FeatureStore
from api.services.feature_serialization import geojson_feature_from_parts
from api.views.features.bbox.params import _detect_world_wide_extent
from api.views.features.bbox.sql_builder import _build_bbox_sql_query
from geo_lib.logging.console import get_tagged_logger
from geo_lib.perf.query_budget import QueryBudget
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()


class BboxQueryResult(NamedTuple):
    """Result of a bounding box query containing features and total count"""
    features: List[Dict]
    total_count: int
    fallback_used: bool = False


def _execute_bbox_query_and_parse(
        sql_query: str,
        params: list,
        public_safe: bool = False,
        include_tags: bool = False,
) -> tuple[list, int]:
    """
    Execute SQL query and parse results into GeoJSON feature DTOs.

    Fetched JSONB is deep-copied before DTO construction so stored properties are never
    mutated in place.

    Returns:
        Tuple of (geojson_features_list, matched_count)
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

    matched_count = int(results[0][3])
    geojson_features = []

    for feature_id, geojson_data, geojson_hash, _matched_count in results:
        if isinstance(geojson_data, str):
            geojson_data = json.loads(geojson_data)
        else:
            geojson_data = copy.deepcopy(geojson_data)

        feature = geojson_feature_from_parts(
            feature_id,
            geojson_data,
            geojson_hash,
            public_safe=public_safe,
            include_tags=include_tags,
        )
        if feature is not None:
            geojson_features.append(feature)

    return geojson_features, matched_count


def get_features_in_bbox(bbox: Tuple[float, float, float, float], user_id: int, tags: List[str] | None = None, match_mode: str = 'AND', collection_id: uuid.UUID | None = None, public_safe: bool = False, include_tags: bool = False, scope: str | None = None) -> BboxQueryResult:
    """
    Get features within bounding box from database using optimized raw SQL query.

    Uses PostgreSQL-specific optimizations:
    - && operator ONLY for spatial queries (fastest with GIST index)
    - GIN index for JSONB tag filtering (@> operator)
    - COUNT(*) OVER() for an honest matched_count when LIMIT is applied
    - ORDER BY id LIMIT via QueryBudget

    Args:
        bbox: Bounding box tuple (min_lon, min_lat, max_lon, max_lat)
        user_id: User ID to filter features by
        tags: Optional list of tags to filter features by
        match_mode: 'AND' (default) or 'OR' for tag combining logic
        collection_id: Optional collection ID to filter features by
        public_safe: If True, excludes private tags from properties (for public shares)
        include_tags: If True and public_safe=True, includes tags in properties (otherwise tags are excluded for public shares)
        scope: Optional scope to filter features by (if None, defaults to filtering for scope__isnull=True)

    Returns:
        BboxQueryResult with features, total_count, and fallback_used flag
    """
    if bbox:
        crosses_dateline, world_wide_extent, _lon_span, _lat_span = _detect_world_wide_extent(bbox)
    else:
        crosses_dateline, world_wide_extent, _lon_span, _lat_span = False, False, 0.0, 0.0

    max_features = QueryBudget(get_required_setting('MAX_FEATURES_PER_REQUEST')).clamp(None)

    table_name = FeatureStore._meta.db_table

    use_spatial_filter = not (crosses_dateline or world_wide_extent)

    bbox_for_query = bbox if use_spatial_filter else None
    sql_query, params = _build_bbox_sql_query(
        table_name, user_id, bbox_for_query, tags, match_mode, collection_id, max_features, scope
    )

    if sql_query == "SELECT 1 WHERE FALSE":
        return BboxQueryResult(features=[], total_count=0, fallback_used=False)

    geojson_features, total_count = _execute_bbox_query_and_parse(
        sql_query, params, public_safe, include_tags
    )

    return BboxQueryResult(features=geojson_features, total_count=total_count, fallback_used=False)
