import json

from django.db import transaction
from django.db.models import Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.feature_scope import require_default_scope_feature
from api.utils.responses import handle_404, error_response
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
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
    require_default_scope_feature(feature)
    feature.delete()
    return JsonResponse({
        'message': 'Feature deleted successfully',
        'feature_id': feature_id
    })


@api_or_login_required_401()
@require_http_methods(["POST"])
def bulk_delete_features_by_tag(request):
    """
    API endpoint to bulk delete all features that have a specific tag.
    Used for deleting system tags along with all their features.
    
    Request body: JSON object with:
    - tag: string (required) - The tag to search for and delete features with
    
    Returns:
    - deleted_count: int - Number of features successfully deleted
    - tag: string - The tag that was searched for
    """
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response('Invalid JSON in request body', 400)

    tag = data.get('tag')
    if not tag:
        return error_response('Tag parameter is required', 400)

    if not isinstance(tag, str):
        return error_response('Tag must be a string', 400)

    with transaction.atomic():
        # Query all features for the current user that have this tag
        # Check both 'tags' and 'system_tags' arrays in the JSONB properties
        features = FeatureStore.objects.filter(
            user=request.user,
            scope__isnull=True,
        ).filter(
            Q(geojson__properties__tags__contains=[tag]) |
            Q(geojson__properties__system_tags__contains=[tag])
        )

        deleted_count = features.count()
        if deleted_count == 0:
            return JsonResponse({
                'deleted_count': 0,
                'tag': tag,
                'message': 'No features found with this tag'
            })

        features.delete()

        return JsonResponse({
            'deleted_count': deleted_count,
            'tag': tag,
            'message': f'Successfully deleted {deleted_count} feature(s)'
        })
