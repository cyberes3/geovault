import traceback

from django.http import JsonResponse, Http404
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import handle_404
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

logger = get_tagged_logger('access')


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
def delete_feature(request, feature_id):
    """
    API endpoint to delete a specific feature.

    URL parameter:
    - feature_id: ID of the feature to delete
    """
    # Get the feature from database and verify user ownership
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

    # Delete the feature
    feature.delete()

    return JsonResponse({
        'message': 'Feature deleted successfully',
        'feature_id': feature_id
    })
