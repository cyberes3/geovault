import json

from django.db import connection
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.services.feature_serialization import build_feature_collection, geojson_feature_from_parts
from api.utils.responses import error_response, success_response
from api.views.features.bbox.execution import get_features_in_bbox
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_features_by_tag(request):
    """
    API endpoint to get all features grouped by tags (both user-generated and system tags).
    Returns separate dictionaries for user_tags and system_tags where keys are tags and values are lists of features with that tag.

    Query parameters:
    - search: optional search query to filter tags by name

    OPTIMIZED VERSION: Uses a highly optimized PostgreSQL query that:
    1. Eliminates UNION ALL by directly aggregating in subquery
    2. Computes tag priority in SQL for efficient sorting
    3. Uses minimal JSON objects to reduce aggregation overhead
    4. Leverages GIN indexes for fast JSONB array operations
    """
    # Get search query
    search_query = request.GET.get('search', '').strip().lower()
    user_id = request.user.id
    table_name = FeatureStore._meta.db_table

    # Build optimized query with tag priority calculation in SQL
    with connection.cursor() as cursor:
        # Build search condition and params
        if search_query:
            search_condition = "AND LOWER(tag_value) LIKE %s"
            search_param = f'%{search_query}%'
            # Double params for user_id in both parts of UNION ALL, with search params
            params = (user_id, search_param, user_id, search_param)
        else:
            search_condition = ""
            # Double params for user_id in both parts of UNION ALL
            params = (user_id, user_id)

        # Optimized query: processes both tag types in one pass with priority calculation
        # Uses array_agg for better performance than json_agg
        # Tag priority is computed in SQL using CASE for 'source-file' prefix (priority 1, else 0)
        query = f"""
            WITH tag_features AS (
                -- Extract user tags
                SELECT 
                    f.id,
                    t.tag_value,
                    'user' AS tag_type,
                    0 AS priority,
                    COALESCE(f.geojson->'properties'->>'name', 'Unnamed Feature') AS feature_name,
                    COALESCE(f.geojson->'geometry'->>'type', 'Unknown') AS geometry_type
                FROM {table_name} f
                CROSS JOIN LATERAL jsonb_array_elements_text(f.geojson->'properties'->'tags') AS t(tag_value)
                WHERE f.user_id = %s
                  AND f.geojson->'properties'->'tags' IS NOT NULL
                  AND jsonb_typeof(f.geojson->'properties'->'tags') = 'array'
                  AND t.tag_value != ''
                  AND f.scope IS NULL
                  {search_condition}

                UNION ALL

                -- Extract system tags with priority calculation
                SELECT 
                    f.id,
                    t.tag_value,
                    'system' AS tag_type,
                    CASE 
                        WHEN LOWER(t.tag_value) = 'source-file' OR LOWER(t.tag_value) LIKE 'source-file:%%' THEN 1
                        ELSE 0
                    END AS priority,
                    COALESCE(f.geojson->'properties'->>'name', 'Unnamed Feature') AS feature_name,
                    COALESCE(f.geojson->'geometry'->>'type', 'Unknown') AS geometry_type
                FROM {table_name} f
                CROSS JOIN LATERAL jsonb_array_elements_text(f.geojson->'properties'->'system_tags') AS t(tag_value)
                WHERE f.user_id = %s
                  AND f.geojson->'properties'->'system_tags' IS NOT NULL
                  AND jsonb_typeof(f.geojson->'properties'->'system_tags') = 'array'
                  AND t.tag_value != ''
                  AND f.scope IS NULL
                  {search_condition}
            )
            SELECT 
                tag_value,
                tag_type,
                priority,
                array_agg(id ORDER BY id) AS feature_ids,
                array_agg(feature_name ORDER BY id) AS feature_names,
                array_agg(geometry_type ORDER BY id) AS geometry_types
            FROM tag_features
            GROUP BY tag_value, tag_type, priority
            ORDER BY 
                tag_type,
                CASE WHEN tag_type = 'system' THEN priority ELSE 0 END,
                LOWER(tag_value)
        """

        cursor.execute(query, params)
        results = cursor.fetchall()

    # Process results - they're already sorted correctly by SQL
    # Build JSON from arrays (faster than json_agg in PostgreSQL)
    user_tags_dict = {}
    system_tags_dict = {}

    for tag, tag_type, priority, feature_ids, feature_names, geometry_types in results:
        # Build feature list from parallel arrays
        features = [
            {
                'properties': {
                    'database_id': fid,
                    'name': fname
                },
                'geometry': {
                    'type': gtype
                }
            }
            for fid, fname, gtype in zip(feature_ids, feature_names, geometry_types)
        ]

        if tag_type == 'user':
            user_tags_dict[tag] = features
        else:
            system_tags_dict[tag] = features

    # Build response - no need to sort in Python, SQL already sorted correctly
    response_data = {
        'user_tags': user_tags_dict,
        'system_tags': system_tags_dict
    }

    return success_response(response_data)


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_user_tags(request):
    """
    Lightweight endpoint to return a sorted list of unique user tags for the
    authenticated user.

    This is optimized for tag autocomplete use-cases and intentionally avoids
    returning any feature data or system tags.

    OPTIMIZED: Leverages GIN index on geojson field for fast JSONB array operations.
    """
    user_id = request.user.id
    table_name = FeatureStore._meta.db_table

    # Use PostgreSQL JSONB functions to efficiently extract distinct user tags
    # The GIN index on the geojson field makes JSONB array operations extremely fast
    with connection.cursor() as cursor:
        query = f"""
            SELECT tag
            FROM (
                SELECT DISTINCT t.tag
                FROM {table_name} f
                CROSS JOIN LATERAL jsonb_array_elements_text(f.geojson->'properties'->'tags') AS t(tag)
                WHERE f.user_id = %s
                  AND jsonb_typeof(f.geojson->'properties'->'tags') = 'array'
                  AND t.tag <> ''
                  AND f.scope IS NULL
            ) AS distinct_tags
            ORDER BY LOWER(tag)
        """
        cursor.execute(query, [user_id])
        tags = [row[0] for row in cursor.fetchall()]

    return JsonResponse(tags, safe=False)


@api_or_login_required_401()
@require_http_methods(["GET"])
def search_features(request):
    """
    API endpoint to search features by name, description, or tags.
    Searches across all user's features, not just those in view.

    Query parameters:
    - query: search text (required)

    OPTIMIZED VERSION: Uses raw SQL with native PostgreSQL operators for maximum performance.
    - Selects only needed fields (id, geojson, geojson_hash) to avoid fetching large geometry field
    - Uses ILIKE with ->> operator for efficient JSONB text search
    - Hard-coded limit of 100 features to prevent excessive data transfer
    - GIN index on geojson field accelerates tag searches
    """
    # Get query parameter
    query = request.GET.get('query', '').strip()

    if not query:
        return error_response('query parameter is required', code=400)

    user_id = request.user.id
    table_name = FeatureStore._meta.db_table
    search_pattern = f'%{query}%'

    # Raw SQL query for maximum performance
    # Searches in name, description, tags array (as text), and system_tags array (as text)
    # For arrays, we convert to text with explicit casting: (geojson->'properties'->'tags')::text
    # LIMIT 100 applied at database level for efficiency
    sql_query = f"""
        SELECT id, geojson, geojson_hash
        FROM {table_name}
        WHERE user_id = %s
          AND geometry IS NOT NULL
          AND scope IS NULL
          AND (
            geojson->'properties'->>'name' ILIKE %s OR
            geojson->'properties'->>'description' ILIKE %s OR
            (geojson->'properties'->'tags')::text ILIKE %s OR
            (geojson->'properties'->'system_tags')::text ILIKE %s
          )
        ORDER BY id
        LIMIT 100
    """

    with connection.cursor() as cursor:
        cursor.execute(sql_query, (user_id, search_pattern, search_pattern, search_pattern, search_pattern))
        results = cursor.fetchall()

    # Convert results to GeoJSON format
    geojson_features = []
    for feature_id, geojson_data, geojson_hash in results:
        # Parse JSON if it's a string (depends on psycopg configuration)
        if isinstance(geojson_data, str):
            geojson_data = json.loads(geojson_data)

        feature = geojson_feature_from_parts(feature_id, geojson_data, geojson_hash)
        if feature is not None:
            geojson_features.append(feature)

    response_data = {
        'data': build_feature_collection(geojson_features),
        'feature_count': len(geojson_features),
        'query': query
    }

    return success_response(response_data)


@api_or_login_required_401()
@require_http_methods(["GET"])
def filter_features_by_tags(request):
    """
    Filter features by tags with support for AND/OR logic and prefix matching.
    Query parameters:
    - tags: list of tag names (can be repeated: ?tags=tag1&tags=tag2)
      - Tags ending with ':' are treated as prefix matches (e.g., 'ski-resort:' matches 'ski-resort:vail')
      - Tags without ':' are exact matches
    - match_mode: 'AND' (default) or 'OR'
      - AND: returns features that have ALL specified tag conditions
      - OR: returns features that have ANY specified tag condition

    OPTIMIZED: Uses GIN index on geojson field for fast JSONB containment operations.
    """
    # Get tags from query parameters (can be multiple)
    tags = request.GET.getlist('tags')

    # Filter out empty tags
    tags = [tag.strip() for tag in tags if tag.strip()]

    if not tags:
        return error_response('At least one tag parameter is required', code=400)

    # Get match mode (default to AND)
    match_mode = request.GET.get('match_mode', 'AND').upper()
    if match_mode not in ['AND', 'OR']:
        return error_response('match_mode must be either AND or OR', code=400)

    # Use the optimized shared logic from the bbox execution module
    # Pass bbox=None to skip spatial filtering and search all features

    # We pass None for bbox to search everywhere
    # The function respects MAX_FEATURES_PER_REQUEST, but for tag filtering 
    # we ideally want all matches. If the limit hits, we might need adjustments
    # but for now we follow the platform standard limit.
    query_result = get_features_in_bbox(
        bbox=None, 
        user_id=request.user.id, 
        tags=tags, 
        match_mode=match_mode
    )
    
    geojson_features = query_result.features

    # Create GeoJSON FeatureCollection
    geojson_data = {
        "type": "FeatureCollection",
        "features": geojson_features
    }

    response_data = {
        'data': geojson_data,
        'feature_count': len(geojson_features),
        'tags': tags,
        'match_mode': match_mode
    }

    return success_response(response_data)


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_all_features(request):
    """
    API endpoint to get all features for the user.
    Returns a list of all features with their basic information for selection purposes.
    """
    # Get all main-map features for the user (extension-scoped features, e.g. `places`,
    # are excluded -- they're surfaced through their own extension API).
    features = FeatureStore.objects.owned_by(request.user).main_map().with_geometry().order_by('id')
    total_count = features.count()

    max_features = get_required_setting('MAX_FEATURES_PER_REQUEST')
    if max_features > 0:
        features = features[:max_features]

    geojson_features = [
        f for f in (geojson_feature_from_parts(feature.id, feature.geojson, feature.geojson_hash) for feature in features)
        if f is not None
    ]

    response_data = {
        'data': build_feature_collection(geojson_features),
        'feature_count': len(geojson_features),
        'total_features': total_count,
        'max_features_limit': max_features,
    }

    return success_response(response_data)
