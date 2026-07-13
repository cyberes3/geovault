"""Bulk operations on features (styling, tags, etc.)"""

from django.db import transaction
from django.db.models import Q
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.services.feature_service import FeatureService
from api.utils.responses import error_response, success_response
from api.validation.decorators import validate_payload
from api.validation.payloads.bulk_operations import SaveBulkOperationsPayload
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(SaveBulkOperationsPayload)
def apply_bulk_operations_to_tag(request, tag_name: str, validated_data):
    """
    Apply bulk operations to all features that have the specified tag.

    This endpoint is used from the Tags page to style all features in a tag
    (point color, point icon, line color, polygon color, and additional tags).

    Request body:
    - bulk_operations: JSON object with the same structure as import bulk operations:
      {
        "tags": [...],
        "pointColor": "#rrggbb" | null,
        "pointIcon": "url" | null,
        "lineColor": "#rrggbb" | null,
        "polyColor": "#rrggbb" | null
      }
    """
    bulk_ops = validated_data.get('bulk_operations', {})

    # Validate tag name
    if not isinstance(tag_name, str) or not tag_name.strip():
        return error_response('Tag name is required', 400)

    # Only operate on the current user's main-map features -- extension-scoped features
    # (e.g. `places`) manage their own styling and must never be touched by this endpoint,
    # even if one happens to share the same tag text.
    # Search in both user tags and system tags
    features_qs = FeatureStore.objects.owned_by(request.user).main_map().filter(
        Q(geojson__properties__tags__contains=[tag_name]) |
        Q(geojson__properties__system_tags__contains=[tag_name])
    ).only('id', 'geojson')

    if not features_qs.exists():
        return success_response({'success': True, 'updated_count': 0, 'msg': 'No features found for this tag'})

    with transaction.atomic():
        updated_count = FeatureService.apply_bulk_operations(features_qs, bulk_ops)

    return success_response({'success': True, 'updated_count': updated_count})
