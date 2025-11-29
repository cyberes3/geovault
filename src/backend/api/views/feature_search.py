import traceback

from django.db import connection
from django.db.models import Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
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
            "_id": feature.id,
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
    - page: page number (default: 1)
    - search: optional search query to filter tags by name
    """
    try:
        # Get pagination parameters
        try:
            page = int(request.GET.get('page', 1))
        except (ValueError, TypeError):
            return JsonResponse({
                'error': 'Invalid pagination parameter. page must be an integer.',
                'code': 400
            }, status=400)
        
        # Hardcoded page size
        page_size = 10
        
        # Get search query
        search_query = request.GET.get('search', '').strip().lower()
        
        # Validate and normalize pagination parameters
        page = max(1, page)

        # Step 1: Get all unique tags using PostgreSQL JSON functions (database-side processing)
        # This is much faster than Python-side iteration - all processing happens in PostgreSQL
        with connection.cursor() as cursor:
            # Use PostgreSQL's jsonb_array_elements_text to extract tags from JSON arrays
            # This processes everything in the database, not in Python
            user_id = request.user.id
            table_name = FeatureStore._meta.db_table
            
            # Build the CTE query to extract unique tags
            # We use UNION to combine user_tags and system_tags, then DISTINCT to get unique values
            # All filtering, sorting, and distinct operations happen in PostgreSQL
            cte_part = f"""
                WITH user_tags AS (
                    SELECT DISTINCT jsonb_array_elements_text(
                        COALESCE(geojson->'properties'->'tags', '[]'::jsonb)
                    ) AS tag
                    FROM {table_name}
                    WHERE user_id = %s
                      AND geojson->'properties'->'tags' IS NOT NULL
                      AND jsonb_typeof(geojson->'properties'->'tags') = 'array'
                ),
                system_tags AS (
                    SELECT DISTINCT jsonb_array_elements_text(
                        COALESCE(geojson->'properties'->'system_tags', '[]'::jsonb)
                    ) AS tag
                    FROM {table_name}
                    WHERE user_id = %s
                      AND geojson->'properties'->'system_tags' IS NOT NULL
                      AND jsonb_typeof(geojson->'properties'->'system_tags') = 'array'
                ),
                all_user_tags AS (
                    SELECT tag, 'user' AS tag_type
                    FROM user_tags
                    WHERE tag != '' AND tag IS NOT NULL
                ),
                all_system_tags AS (
                    SELECT tag, 'system' AS tag_type
                    FROM system_tags
                    WHERE tag != '' AND tag IS NOT NULL
                ),
                combined_tags AS (
                    SELECT tag, tag_type FROM all_user_tags
                    UNION ALL
                    SELECT tag, tag_type FROM all_system_tags
                ),
                filtered_tags AS (
                    SELECT tag, tag_type
                    FROM combined_tags
            """
            
            # Add search filter if provided (database-side filtering)
            params = [user_id, user_id]
            if search_query:
                cte_part += " WHERE LOWER(tag) LIKE %s"
                params.append(f'%{search_query}%')
            
            cte_part += ") "
            
            # Get total count for pagination (database-side count)
            count_query = cte_part + "SELECT COUNT(*) FROM filtered_tags"
            cursor.execute(count_query, params)
            total_tags = cursor.fetchone()[0]
            total_pages = (total_tags + page_size - 1) // page_size if total_tags > 0 else 0
            
            # Build paginated query (LIMIT/OFFSET) - all done in database
            offset = (page - 1) * page_size
            paginated_query = cte_part + "SELECT tag, tag_type FROM filtered_tags ORDER BY tag LIMIT %s OFFSET %s"
            params.extend([page_size, offset])
            
            cursor.execute(paginated_query, params)
            paginated_tags = [{'tag': row[0], 'type': row[1]} for row in cursor.fetchall()]
        
        # Step 3: Fetch features only for tags on the current page
        # This is the key optimization - we only query features for tags we'll return
        user_tags_to_fetch = [tag_info['tag'] for tag_info in paginated_tags if tag_info['type'] == 'user']
        system_tags_to_fetch = [tag_info['tag'] for tag_info in paginated_tags if tag_info['type'] == 'system']
        
        # Build dictionaries for features by tag
        features_by_user_tag = {tag: [] for tag in user_tags_to_fetch}
        features_by_system_tag = {tag: [] for tag in system_tags_to_fetch}
        
        # Only query features that have tags on the current page
        if user_tags_to_fetch or system_tags_to_fetch:
            # Build query to get features with any of the tags we need
            tag_query = Q()
            for tag in user_tags_to_fetch:
                tag_query |= Q(geojson__properties__tags__contains=[tag])
            for tag in system_tags_to_fetch:
                tag_query |= Q(geojson__properties__system_tags__contains=[tag])
            
            # Fetch only needed fields
            features_to_process = FeatureStore.objects.filter(
                user=request.user
            ).filter(tag_query).only('id', 'geojson')
            
            # Process features and assign to tags
            for feature in features_to_process.iterator(chunk_size=1000):
                minimal_feature = _create_minimal_feature(feature)
                if not minimal_feature:
                    continue

                geojson_data = feature.geojson
                properties = geojson_data.get('properties', {})
                feature_user_tags = _normalize_tags(properties.get('tags', []))
                feature_system_tags = _normalize_tags(properties.get('system_tags', []))

                # Add feature to relevant tags (only for tags on current page)
                for tag in feature_user_tags:
                    if tag in features_by_user_tag:
                        features_by_user_tag[tag].append(minimal_feature)

                for tag in feature_system_tags:
                    if tag in features_by_system_tag:
                        features_by_system_tag[tag].append(minimal_feature)
        
        # Build response with paginated tags
        response_data = {
            'user_tags': {},
            'system_tags': {},
            'pagination': {
                'page': page,
                'page_size': page_size,
                'total_tags': total_tags,
                'total_pages': total_pages,
                'has_next': page < total_pages,
                'has_previous': page > 1
            }
        }

        # Add features for paginated tags
        for tag_info in paginated_tags:
            if tag_info['type'] == 'user':
                response_data['user_tags'][tag_info['tag']] = features_by_user_tag.get(tag_info['tag'], [])
            else:
                response_data['system_tags'][tag_info['tag']] = features_by_system_tag.get(tag_info['tag'], [])

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error getting features by tag: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to get features by tag',
            'code': 500
        }, status=500)


@api_or_login_required_401()
@require_http_methods(["GET"])
def search_features(request):
    """
    API endpoint to search features by name, description, or tags.
    Searches across all user's features, not just those in view.
    
    Query parameters:
    - query: search text (required)
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
                properties['_id'] = feature.id
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
            # This uses PostgreSQL's JSON containment operator
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
                properties['_id'] = feature.id
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
                properties['_id'] = feature.id
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
