import traceback

from django.db.models import Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.const_strings import CONST_INTERNAL_TAGS
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


@login_required_401
@require_http_methods(["GET"])
def get_features_by_tag(request):
    """
    API endpoint to get all features grouped by tags (both user-generated and system tags).
    Returns separate dictionaries for user_tags and system_tags where keys are tags and values are lists of features with that tag.
    """
    try:
        # Get all features for the user
        features = FeatureStore.objects.filter(user=request.user)

        # Dictionaries to store features by tag type
        features_by_user_tag = {}
        features_by_system_tag = {}

        # Iterate through all features
        for feature in features:
            geojson_data = feature.geojson
            if not geojson_data or 'properties' not in geojson_data:
                continue

            properties = geojson_data.get('properties', {})
            user_tags = properties.get('tags', [])
            system_tags = properties.get('system_tags', [])
            
            if not isinstance(user_tags, list):
                user_tags = []
            if not isinstance(system_tags, list):
                system_tags = []

            # Create minimal feature object with only what's needed for display
            geometry_data = geojson_data.get('geometry', {})
            minimal_feature = {
                "properties": {
                    "_id": feature.id,
                    "name": properties.get('name', 'Unnamed Feature'),
                    "description": properties.get('description', '')
                },
                "geometry": {
                    "type": geometry_data.get('type', 'Unknown') if isinstance(geometry_data, dict) else 'Unknown'
                }
            }

            # Add feature to each user tag's list
            for tag in user_tags:
                if isinstance(tag, str) and tag:  # Ensure tag is a non-empty string
                    if tag not in features_by_user_tag:
                        features_by_user_tag[tag] = []
                    features_by_user_tag[tag].append(minimal_feature)

            # Add feature to each system tag's list
            for tag in system_tags:
                if isinstance(tag, str) and tag:  # Ensure tag is a non-empty string
                    if tag not in features_by_system_tag:
                        features_by_system_tag[tag] = []
                    features_by_system_tag[tag].append(minimal_feature)

        # Sort tags alphabetically
        sorted_user_tags = sorted(features_by_user_tag.keys())
        sorted_system_tags = sorted(features_by_system_tag.keys())

        # Build response with sorted tags
        response_data = {
            'user_tags': {},
            'system_tags': {}
        }

        for tag in sorted_user_tags:
            response_data['user_tags'][tag] = features_by_user_tag[tag]

        for tag in sorted_system_tags:
            response_data['system_tags'][tag] = features_by_system_tag[tag]

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error getting features by tag: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to get features by tag',
            'code': 500
        }, status=500)


@login_required_401
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
                # Include database ID in properties for frontend editing
                properties = geojson_data.get('properties', {}).copy()
                properties['_id'] = feature.id
                geojson_feature = {
                    "type": "Feature",
                    "geometry": geojson_data.get('geometry'),
                    "properties": properties,
                    "geojson_hash": feature.file_hash
                }
                geojson_features.append(geojson_feature)

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


@login_required_401
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
                
                # Tags are already separated - user tags only in tags field
                # System tags are in system_tags field and not shown to user
                
                # Include database ID in properties for frontend editing
                properties['_id'] = feature.id
                
                geojson_feature = {
                    "type": "Feature",
                    "geometry": geojson_data.get('geometry'),
                    "properties": properties,
                    "geojson_hash": feature.file_hash
                }
                geojson_features.append(geojson_feature)
        
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


@login_required_401
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
                
                # Tags are already separated - user tags only in tags field
                # System tags are in system_tags field and not shown to user
                
                # Include database ID in properties
                properties['_id'] = feature.id
                
                geojson_feature = {
                    "type": "Feature",
                    "geometry": geojson_data.get('geometry'),
                    "properties": properties,
                    "geojson_hash": feature.file_hash
                }
                geojson_features.append(geojson_feature)
        
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
