import traceback

from django.db import connection
from django.db.models import Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.const_strings import get_tag_priority
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import api_or_login_required_401

logger = get_access_logger()


def _create_minimal_feature(feature):
    """
    Create a minimal feature object with only essential fields for display.
    
    Args:
        feature: FeatureStore instance
        
    Returns:
        dict: Minimal feature object with id, name, description, and geometry type
    """
    geojson_data = feature.geojson
    if not geojson_data or 'properties' not in geojson_data:
        return None
    
    properties = geojson_data.get('properties', {})
    geometry_data = geojson_data.get('geometry', {})
    
    return {
        "properties": {
            "database_id": feature.id,
            "name": properties.get('name', 'Unnamed Feature'),
            "description": properties.get('description', '')
        },
        "geometry": {
            "type": geometry_data.get('type', 'Unknown') if isinstance(geometry_data, dict) else 'Unknown'
        }
    }


def _normalize_tags(tags):
    """
    Normalize tags to ensure they're a list of strings.
    
    Args:
        tags: Tags value (could be list, None, or other)
        
    Returns:
        list: Normalized list of tag strings
    """
    if not isinstance(tags, list):
        return []
    return [tag for tag in tags if isinstance(tag, str) and tag]


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_features_by_tag(request):
    """
    API endpoint to get all features grouped by tags (both user-generated and system tags).
    Returns separate dictionaries for user_tags and system_tags where keys are tags and values are lists of features with that tag.
    
    Query parameters:
    - search: optional search query to filter tags by name
    
    OPTIMIZED VERSION: Uses a single PostgreSQL CTE query that processes everything in the database,
    avoiding Python-side iteration and leveraging GIN indexes for fast JSONB array operations.
    """
    # Get search query
    search_query = request.GET.get('search', '').strip().lower()
    user_id = request.user.id
    table_name = FeatureStore._meta.db_table

    # Build single optimized query that does everything in PostgreSQL:
    # 1. Extract all tag-feature pairs using LATERAL joins
    # 2. Build minimal feature JSON objects in the database
    # 3. Aggregate features by tag using json_agg
    # 4. Return pre-grouped results
    with connection.cursor() as cursor:
        # Build parameters list
        # Order: user_id (first CTE), [search (first CTE)], user_id (second CTE), [search (second CTE)]
        if search_query:
            search_condition = "AND LOWER(tag) LIKE %s"
            search_param = f'%{search_query}%'
            params = [user_id, search_param, user_id, search_param]
        else:
            search_condition = ""
            params = [user_id, user_id]
        
        # Single optimized CTE query that does all processing in PostgreSQL
        query = f"""
            WITH 
            -- Extract user tag-feature pairs
            user_tag_features AS (
                SELECT 
                    f.id,
                    tag.tag,
                    'user' AS tag_type,
                    f.geojson->'properties'->>'name' AS feature_name,
                    f.geojson->'properties'->>'description' AS feature_description,
                    f.geojson->'geometry'->>'type' AS geometry_type
                FROM {table_name} f
                CROSS JOIN LATERAL jsonb_array_elements_text(f.geojson->'properties'->'tags') AS tag(tag)
                WHERE f.user_id = %s
                  AND f.geojson->'properties'->'tags' IS NOT NULL
                  AND jsonb_typeof(f.geojson->'properties'->'tags') = 'array'
                  AND tag.tag != ''
                  {search_condition}
            ),
            -- Extract system tag-feature pairs
            system_tag_features AS (
                SELECT 
                    f.id,
                    tag.tag,
                    'system' AS tag_type,
                    f.geojson->'properties'->>'name' AS feature_name,
                    f.geojson->'properties'->>'description' AS feature_description,
                    f.geojson->'geometry'->>'type' AS geometry_type
                FROM {table_name} f
                CROSS JOIN LATERAL jsonb_array_elements_text(f.geojson->'properties'->'system_tags') AS tag(tag)
                WHERE f.user_id = %s
                  AND f.geojson->'properties'->'system_tags' IS NOT NULL
                  AND jsonb_typeof(f.geojson->'properties'->'system_tags') = 'array'
                  AND tag.tag != ''
                  {search_condition}
            ),
            -- Combine all tag-feature pairs
            all_tag_features AS (
                SELECT * FROM user_tag_features
                UNION ALL
                SELECT * FROM system_tag_features
            ),
            -- Aggregate features by tag using json_agg
            aggregated_tags AS (
                SELECT 
                    tag,
                    tag_type,
                    json_agg(
                        json_build_object(
                            'properties', json_build_object(
                                'database_id', id,
                                'name', COALESCE(feature_name, 'Unnamed Feature'),
                                'description', COALESCE(feature_description, '')
                            ),
                            'geometry', json_build_object(
                                'type', COALESCE(geometry_type, 'Unknown')
                            )
                        )
                    ) AS features
                FROM all_tag_features
                GROUP BY tag, tag_type
            )
            SELECT tag, tag_type, features
            FROM aggregated_tags
            ORDER BY tag
        """
        
        cursor.execute(query, params)
        results = cursor.fetchall()
    
    # Process results and separate by tag type
    user_tags_dict = {}
    system_tags_dict = {}
    
    for tag, tag_type, features_json in results:
        if tag_type == 'user':
            user_tags_dict[tag] = features_json
        else:
            system_tags_dict[tag] = features_json
    
    # Sort tags according to requirements:
    # - User tags: alphabetically
    # - System tags: by priority then alphabetically
    user_tags_sorted = dict(sorted(user_tags_dict.items(), key=lambda x: x[0].lower()))
    system_tags_sorted = dict(sorted(
        system_tags_dict.items(),
        key=lambda x: (get_tag_priority(x[0]), x[0].lower())
    ))
    
    # Build response
    response_data = {
        'user_tags': user_tags_sorted,
        'system_tags': system_tags_sorted
    }
    
    return JsonResponse(response_data)


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
    
    NOTE: Tag searches benefit from GIN index on geojson field for fast JSONB operations.
    """
    # Get query parameter
    query = request.GET.get('query', '').strip()

    if not query:
        return JsonResponse({
            'error': 'query parameter is required',
            'code': 400
        }, status=400)

    try:
        # Base query for user's features
        base_query = FeatureStore.objects.filter(user=request.user).exclude(geometry__isnull=True)

        # Build search query using Q objects for OR conditions
        # Search in name, description, tags, and system_tags fields
        # Use PostgreSQL JSON field lookups with case-insensitive contains
        # The GIN index on geojson field accelerates tag searches
        search_q = (
                Q(geojson__properties__name__icontains=query) |
                Q(geojson__properties__description__icontains=query) |
                Q(geojson__properties__tags__icontains=query) |
                Q(geojson__properties__system_tags__icontains=query)
        )

        # Apply search filter
        features_query = base_query.filter(search_q).order_by('id')

        # Convert to GeoJSON format
        geojson_features = []
        for feature in features_query:
            geojson_data = feature.geojson
            if geojson_data and 'geometry' in geojson_data:
                properties = geojson_data.get('properties', {}).copy()
                properties['database_id'] = feature.id
                geojson_features.append({
                    "type": "Feature",
                    "geometry": geojson_data.get('geometry'),
                    "properties": properties,
                    "geojson_hash": feature.geojson_hash
                })

        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": geojson_features
        }

        response_data = {
            'data': geojson_data,
            'feature_count': len(geojson_features),
            'query': query
        }

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error searching features: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to search features',
            'code': 500
        }, status=500)


@api_or_login_required_401()
@require_http_methods(["GET"])
def filter_features_by_tags(request):
    """
    Filter features by tags using AND logic.
    Query parameters:
    - tags: list of tag names (can be repeated: ?tags=tag1&tags=tag2)
    Returns features that have ALL specified tags.
    
    OPTIMIZED: Uses GIN index on geojson field for fast JSONB containment operations.
    """
    # Get tags from query parameters (can be multiple)
    tags = request.GET.getlist('tags')
    
    # Filter out empty tags
    tags = [tag.strip() for tag in tags if tag.strip()]
    
    if not tags:
        return JsonResponse({
            'error': 'At least one tag parameter is required',
            'code': 400
        }, status=400)
    
    try:
        # Base query for user's features
        base_query = FeatureStore.objects.filter(user=request.user).exclude(geometry__isnull=True)
        
        # Filter features that have ALL specified tags (AND logic)
        # We need to check that each tag is present in the feature's tags array
        features_query = base_query
        
        for tag in tags:
            # Use JSON field lookup to check if tag exists in either tags or system_tags array
            # Uses PostgreSQL's @> containment operator, accelerated by GIN index
            features_query = features_query.filter(
                Q(geojson__properties__tags__contains=[tag]) |
                Q(geojson__properties__system_tags__contains=[tag])
            )
        
        # Convert to GeoJSON format
        geojson_features = []
        for feature in features_query.order_by('id'):
            geojson_data = feature.geojson
            if geojson_data and 'geometry' in geojson_data:
                properties = geojson_data.get('properties', {}).copy()
                properties['database_id'] = feature.id
                geojson_features.append({
                    "type": "Feature",
                    "geometry": geojson_data.get('geometry'),
                    "properties": properties,
                    "geojson_hash": feature.geojson_hash
                })
        
        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": geojson_features
        }
        
        response_data = {
            'data': geojson_data,
            'feature_count': len(geojson_features),
            'tags': tags
        }
        
        return JsonResponse(response_data)
    
    except Exception:
        logger.error(f"Error filtering features by tags: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to filter features by tags',
            'code': 500
        }, status=500)


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_all_features(request):
    """
    API endpoint to get all features for the user.
    Returns a list of all features with their basic information for selection purposes.
    """
    try:
        # Get all features for the user
        features = FeatureStore.objects.filter(user=request.user).exclude(geometry__isnull=True).order_by('id')
        
        # Convert to GeoJSON format
        geojson_features = []
        for feature in features:
            geojson_data = feature.geojson
            if geojson_data and 'geometry' in geojson_data:
                properties = geojson_data.get('properties', {}).copy()
                properties['database_id'] = feature.id
                geojson_features.append({
                    "type": "Feature",
                    "geometry": geojson_data.get('geometry'),
                    "properties": properties,
                    "geojson_hash": feature.geojson_hash
                })
        
        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": geojson_features
        }
        
        response_data = {
            'data': geojson_data,
            'feature_count': len(geojson_features)
        }
        
        return JsonResponse(response_data)
    
    except Exception:
        logger.error(f"Error getting all features: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to get all features',
            'code': 500
        }, status=500)
