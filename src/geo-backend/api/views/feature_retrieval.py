import traceback

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


@login_required_401
@require_http_methods(["GET"])
def get_feature(request, feature_id):
    """
    API endpoint to get a specific feature by ID.

    URL parameter:
    - feature_id: ID of the feature to retrieve
    """
    try:
        # Get the feature from database
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Include database ID in properties for frontend editing (same as _get_features_in_bbox)
        geojson_data = feature.geojson.copy()
        if geojson_data and 'properties' in geojson_data:
            geojson_data['properties']['_id'] = feature.id

        # Return the feature data
        return JsonResponse({
            'success': True,
            'feature': {
                'id': feature.id,
                'geojson': geojson_data,
                'geojson_hash': feature.geojson_hash,
                'timestamp': feature.timestamp.isoformat() if feature.timestamp else None
            }
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception:
        logger.error(f"Error getting feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get feature',
            'code': 500
        }, status=500)
