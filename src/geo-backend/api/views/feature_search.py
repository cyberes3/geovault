import traceback

from django.db.models import Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


@login_required_401
@require_http_methods(["GET"])
def get_features_by_tag(request):
    """
    API endpoint to get all features grouped by user-generated tags.
    Returns a dictionary where keys are tags and values are lists of features with that tag.
    """
    try:
        # Get all features for the user
        features = FeatureStore.objects.filter(user=request.user)

        # Dictionary to store features by tag
        features_by_tag = {}

        # Iterate through all features
        for feature in features:
            geojson_data = feature.geojson
            if not geojson_data or 'properties' not in geojson_data:
                continue

            properties = geojson_data.get('properties', {})
            tags = properties.get('tags', [])

            if not isinstance(tags, list):
                continue

            # Filter out protected tags to only show user-generated tags
            filtered_tags = filter_protected_tags(tags, CONST_INTERNAL_TAGS)

            # Include database ID in properties for frontend editing
            feature_properties = properties.copy()
            feature_properties['_id'] = feature.id

            # Create GeoJSON feature
            geojson_feature = {
                "type": "Feature",
                "geometry": geojson_data.get('geometry'),
                "properties": feature_properties,
                "geojson_hash": feature.file_hash
            }

            # Add feature to each tag's list
            for tag in filtered_tags:
                if isinstance(tag, str) and tag:  # Ensure tag is a non-empty string
                    if tag not in features_by_tag:
                        features_by_tag[tag] = []
                    features_by_tag[tag].append(geojson_feature)

        # Sort tags alphabetically
        sorted_tags = sorted(features_by_tag.keys())

        # Build response with sorted tags
        response_data = {
            'success': True,
            'tags': {}
        }

        for tag in sorted_tags:
            response_data['tags'][tag] = features_by_tag[tag]

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error getting features by tag: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
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
            'success': False,
            'error': 'query parameter is required',
            'code': 400
        }, status=400)

    try:
        # Base query for user's features
        base_query = FeatureStore.objects.filter(user=request.user).exclude(geometry__isnull=True)

        # Build search query using Q objects for OR conditions
        # Search in name, description, and tags fields
        # Use PostgreSQL JSON field lookups with case-insensitive contains
        search_q = (
                Q(geojson__properties__name__icontains=query) |
                Q(geojson__properties__description__icontains=query) |
                Q(geojson__properties__tags__icontains=query)
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
            'success': True,
            'data': geojson_data,
            'feature_count': len(geojson_features),
            'query': query
        }

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error searching features: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to search features',
            'code': 500
        }, status=500)
