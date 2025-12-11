"""Bulk operations on features (styling, tags, etc.)"""
import json

from django.db import transaction
from django.db.models import Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.utils.responses import error_response
from api.validation.bulk_opts import validate_bulk_operations_payload
from api.views.features.updates.shared import _validate_and_preserve_feature
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.import_operations.styling import apply_bulk_operations as apply_bulk_operations_to_features
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()


def _apply_bulk_ops_and_save_feature(feature: FeatureStore, bulk_ops: dict) -> bool:
    """
    Apply bulk operations to a feature, validate, and save.
    
    Args:
        feature: FeatureStore instance to update
        bulk_ops: Bulk operations dictionary
        
    Returns:
        True if feature was successfully updated, False if skipped due to error
    """
    original_geojson = feature.geojson
    if not isinstance(original_geojson, dict):
        return False

    # Apply bulk operations
    updated_features = apply_bulk_operations_to_features([original_geojson], bulk_ops)
    if not updated_features:
        return False

    updated_geojson = updated_features[0]

    # Validate and normalize the updated feature
    try:
        normalized_feature = _validate_and_preserve_feature(updated_geojson)
    except GeometryValidationError as e:
        _logger.warning(f"Feature validation failed for feature {feature.id} in bulk operations: {str(e)}")
        return False

    # Update feature geojson and hash (geometry is unchanged by styling)
    feature.geojson = normalized_feature
    feature.geojson_hash = generate_geojson_hash(normalized_feature)
    feature.save(update_fields=['geojson', 'geojson_hash'])

    return True


@api_or_login_required_401()
@require_http_methods(["POST"])
def apply_bulk_operations_to_tag(request, tag_name: str):
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
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response('Invalid JSON in request body', 400)

    if not isinstance(data, dict):
        return error_response('Request body must be a valid JSON object', 400)

    bulk_ops = data.get('bulk_operations', {})
    is_valid, error_message = validate_bulk_operations_payload(bulk_ops)
    if not is_valid:
        return error_response(error_message, 400)

    # Validate tag name
    if not isinstance(tag_name, str) or not tag_name.strip():
        return error_response('Tag name is required', 400)

    # Only operate on the current user's features
    # Search in both user tags and system tags
    features_qs = FeatureStore.objects.filter(
        user=request.user
    ).filter(
        Q(geojson__properties__tags__contains=[tag_name]) |
        Q(geojson__properties__system_tags__contains=[tag_name])
    ).only('id', 'geojson')

    if not features_qs.exists():
        return JsonResponse({
            'success': True,
            'updated_count': 0,
            'msg': 'No features found for this tag'
        })

    updated_count = 0

    with transaction.atomic():
        for feature in features_qs.iterator(chunk_size=200):
            if _apply_bulk_ops_and_save_feature(feature, bulk_ops):
                updated_count += 1

    return JsonResponse({
        'success': True,
        'updated_count': updated_count
    })
