import traceback

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


@login_required_401
@require_http_methods(["DELETE"])
def delete_feature(request, feature_id):
    """
    API endpoint to delete a specific feature.

    URL parameter:
    - feature_id: ID of the feature to delete
    """
    try:
        # Get the feature from database and verify user ownership
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Delete the feature
        feature.delete()

        return JsonResponse({
            'success': True,
            'message': 'Feature deleted successfully',
            'feature_id': feature_id
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error deleting feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to delete feature',
            'code': 500
        }, status=500)
