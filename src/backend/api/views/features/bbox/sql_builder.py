"""Raw-SQL query builders for the aggressively-optimized bbox feature lookup path."""
import json
import uuid
from typing import Any, List, Tuple

from api.models import Collection
from api.views.collections.utils import get_collection_feature_ids


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
