"""Bulk operations on collections"""
import json

from django.db import transaction
from django.views.decorators.http import require_http_methods

from api.models import Collection, FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response, handle_404
from api.views.collections.utils import get_collection_feature_ids
from api.views.features.updates.bulk_operations import _apply_bulk_ops_and_save_feature
from api.validation.bulk_opts import validate_bulk_operations_payload
from geo_lib.website.auth import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
def apply_bulk_operations_to_collection(request, collection_id):
    """
    Apply bulk operations to all features in a collection.

    This reuses the same bulk operations structure as the import process:
    {
      "bulk_operations": {
        "tags": [...],
        "pointColor": "#rrggbb" | null,
        "pointIcon": "url" | null,
        "lineColor": "#rrggbb" | null,
        "polyColor": "#rrggbb" | null
      }
    }
    """
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response('Invalid JSON in request body', code=400)

    if not isinstance(data, dict):
        return error_response('Request body must be a valid JSON object', code=400)

    bulk_ops = data.get("bulk_operations", {})
    is_valid, error_message = validate_bulk_operations_payload(bulk_ops)
    if not is_valid:
        return error_response(error_message, code=400)

    collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)

    # Build the same feature ID set used by get_collection_features/_count_collection_features
    feature_ids_set = get_collection_feature_ids(collection)

    if not feature_ids_set:
        return success_response({
            "updated_count": 0,
            "msg": "No features found for this collection"
        })

    updated_count = 0

    # Iterate through all features and apply bulk operations using shared helper
    features_qs = FeatureStore.objects.filter(id__in=feature_ids_set).only("id", "geojson")

    # Wrap in transaction to ensure atomicity
    with transaction.atomic():
        for feature in features_qs.iterator(chunk_size=200):
            if _apply_bulk_ops_and_save_feature(feature, bulk_ops):
                updated_count += 1

    return success_response({
        "updated_count": updated_count,
        "msg": f"Successfully updated {updated_count} feature(s) in collection"
    })
