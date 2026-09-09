"""Bulk operations on collections"""

from django.db import transaction
from django.views.decorators.http import require_http_methods

from api.services.collection_service import CollectionService
from api.utils.responses import handle_404, success_response
from api.validation.decorators import validate_payload
from api.validation.payloads.bulk_operations import SaveBulkOperationsPayload
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@validate_payload(SaveBulkOperationsPayload)
def apply_bulk_operations_to_collection(request, collection_id, validated_data):
    bulk_ops = validated_data.get("bulk_operations", {})
    collection = CollectionService.get_owned_or_404(request.user, collection_id)

    with transaction.atomic():
        updated_count = CollectionService.apply_bulk_operations(collection, bulk_ops)

    return success_response({
        "updated_count": updated_count,
        "msg": f"Successfully updated {updated_count} feature(s) in collection"
    })
