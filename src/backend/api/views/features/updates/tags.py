"""Tag regeneration operations"""
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.services.feature_service import FeatureService, UnsupportedFeatureGeometryError
from api.utils.responses import error_response, handle_404
from geo_lib.logging.console import get_tagged_logger
from geo_lib.validation.geometry_validation import GeometryValidationError
from website.auth_decorators import api_or_login_required_401

logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
def regenerate_feature_tags(request, feature_id):
    """
    API endpoint to regenerate automatic tags for a feature based on its current geometry.
    Preserves existing non-auto tags (user-generated tags that don't match auto tag patterns).

    URL parameter:
    - feature_id: ID of the feature to regenerate tags for
    """
    # get_owned_feature_or_404 restricts to main-map features by default -- extension-scoped
    # features (e.g. `places`) manage their own tags through their own service.
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

    try:
        FeatureService.regenerate_tags(feature)
    except UnsupportedFeatureGeometryError as e:
        return error_response(str(e), 400)
    except GeometryValidationError as e:
        logger.error(f"Feature validation failed for feature {feature_id} during tag regeneration: {str(e)}")
        return error_response(f'Feature validation failed: {str(e)}', 400)

    properties = feature.geojson.get('properties', {})
    return JsonResponse({
        'message': 'Feature tags regenerated successfully',
        'feature_id': feature.id,
        'tags': properties.get('tags', []),
        'system_tags': properties.get('system_tags', [])
    })
