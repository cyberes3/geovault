"""Raw-SQL query builders for the bbox feature lookup path."""
import uuid
from typing import Any, List, Tuple

from api.models import Collection
from geo_lib.collections.membership import CollectionMembership
from geo_lib.perf.query_budget import QueryBudgetError
from geo_lib.tags.tag_query import TagQuery, TagQueryError


def _build_tags_sql_filter(
    tags: List[str],
    match_mode: str = 'AND',
    *,
    feature_id_sql: str,
) -> Tuple[str, List[Any]]:
    if not tags:
        return "", []
    try:
        query = TagQuery.parse(tags, match_mode=match_mode, prefix=True, scope=None)
    except TagQueryError:
        return "", []
    return query.to_sql_clause(feature_id_sql=feature_id_sql)


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
    Build optimized SQL query for bbox queries.

    Always ORDER BY id LIMIT, with COUNT(*) OVER() for an honest matched_count
    when the budget truncates the result set.

    Returns:
        Tuple of (sql_query_string, parameters_list)
    """
    if max_features <= 0:
        raise QueryBudgetError("requested limit must be a positive integer")
    min_lon, min_lat, max_lon, max_lat = bbox if bbox else (None, None, None, None)
    params = []

    spatial_filter = ""
    if bbox is not None:
        spatial_filter = " AND geometry && ST_MakeEnvelope(%s::double precision, %s::double precision, %s::double precision, %s::double precision, 4326)"
        params.extend([min_lon, min_lat, max_lon, max_lat])

    tag_filter, tag_params = _build_tags_sql_filter(
        tags or [],
        match_mode,
        feature_id_sql=f'{table_name}.id',
    )
    params.extend(tag_params)

    collection_filter = ""
    if collection_id is not None:
        try:
            collection = Collection.objects.get(id=collection_id, user_id=user_id)
        except Collection.DoesNotExist:
            return ("SELECT 1 WHERE FALSE", [])
        feature_ids_set = CollectionMembership.feature_ids(collection)
        if feature_ids_set:
            placeholders = ','.join(['%s'] * len(feature_ids_set))
            collection_filter = f" AND id IN ({placeholders})"
            params.extend(list(feature_ids_set))
        else:
            return ("SELECT 1 WHERE FALSE", [])

    scope_filter = ""
    if scope is None:
        scope_filter = " AND scope IS NULL"
    else:
        scope_filter = " AND scope = %s"
        params.append(scope)

    params.insert(0, user_id)
    params.append(max_features)

    sql_query = f"""
        SELECT id, geojson, geojson_hash, COUNT(*) OVER() AS matched_count
        FROM {table_name}
        WHERE user_id = %s AND geometry IS NOT NULL{spatial_filter}{tag_filter}{collection_filter}{scope_filter}
        ORDER BY id
        LIMIT %s
    """

    return sql_query, params
