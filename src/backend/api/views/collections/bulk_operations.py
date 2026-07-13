"""Bulk operations on collections"""

from django.db import transaction
from django.views.decorators.http import require_http_methods

from api.models import Collection, FeatureStore
from api.services.feature_service import FeatureService
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import success_response, handle_404
from api.views.collections.utils import get_collection_feature_ids
from api.validation.decorators import validate_payload
from api.validation.payloads.bulk_operations import SaveBulkOperationsPayload
from geo_lib.website.auth import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@validate_payload(SaveBulkOperationsPayload)
def apply_bulk_operations_to_collection(request, collection_id, validated_data):
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
    bulk_ops = validated_data.get("bulk_operations", {})

    collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)

    # Build the same feature ID set used by get_collection_features/_count_collection_features
    feature_ids_set = get_collection_feature_ids(collection)

    if not feature_ids_set:
        return success_response({
            "updated_count": 0,
            "msg": "No features found for this collection"
        })

    # get_collection_feature_ids already scopes to collection.user's features; collections
    # can legitimately span any scope (they're a user-defined grouping, not a map view).
    features_qs = FeatureStore.objects.filter(id__in=feature_ids_set).only("id", "geojson")

    # Wrap in transaction to ensure atomicity
    with transaction.atomic():
        updated_count = FeatureService.apply_bulk_operations(features_qs, bulk_ops)

    return success_response({
        "updated_count": updated_count,
        "msg": f"Successfully updated {updated_count} feature(s) in collection"
    })
