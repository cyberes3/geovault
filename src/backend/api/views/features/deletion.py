from django.db import transaction
from django.db.models import Q
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.services.feature_service import FeatureService
from api.utils.responses import handle_404, success_response
from api.validation.decorators import validate_payload
from api.validation.payloads.features import BulkDeleteByTagPayload
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
def delete_feature(request, feature_id):
    """
    API endpoint to delete a specific feature.

    URL parameter:
    - feature_id: ID of the feature to delete
    """
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)
    feature.delete()
    return success_response({
        'message': 'Feature deleted successfully',
        'feature_id': feature_id
    })


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(BulkDeleteByTagPayload)
def bulk_delete_features_by_tag(request, validated_data):
    """
    API endpoint to bulk delete all features that have a specific tag.
    Used for deleting system tags along with all their features.
    
    Request body: JSON object with:
    - tag: string (required) - The tag to search for and delete features with
    
    Returns:
    - deleted_count: int - Number of features successfully deleted
    - tag: string - The tag that was searched for
    """
    tag = validated_data['tag']

    with transaction.atomic():
        # Query all main-map features for the current user that have this tag (extension-
        # scoped features, e.g. `places`, are never touched by this endpoint).
        # Check both 'tags' and 'system_tags' arrays in the JSONB properties
        features = FeatureStore.objects.owned_by(request.user).main_map().filter(
            Q(geojson__properties__tags__contains=[tag]) |
            Q(geojson__properties__system_tags__contains=[tag])
        )

        deleted_count = features.count()
        if deleted_count == 0:
            return success_response({
                'deleted_count': 0,
                'tag': tag,
                'message': 'No features found with this tag'
            })

        features.delete()

        return success_response({
            'deleted_count': deleted_count,
            'tag': tag,
            'message': f'Successfully deleted {deleted_count} feature(s)'
        })
