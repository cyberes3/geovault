"""Import bulk operations management"""

from django.views.decorators.http import require_http_methods

from api.models import ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response, handle_404
from api.validation.decorators import validate_payload
from api.validation.payloads.bulk_operations import SaveBulkOperationsPayload
from api.validation.payloads.imports import SkipStatePayload
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from website.auth_decorators import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["PUT", "PATCH"])
@handle_404
@validate_payload(SaveBulkOperationsPayload)
def save_bulk_operations(request, item_id, validated_data):
    """
    Save bulk operations (tags, styling) for an import queue item.
    These operations will be applied during import.
    """
    import_item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    # Prevent updating items that have already been imported
    if import_item.imported:
        return error_response(
            'Cannot update bulk operations for items that have already been imported',
            code=400
        )

    # Save bulk operations to the import queue item
    import_item.bulk_operations = validated_data.get('bulk_operations', {})
    import_item.save(update_fields=['bulk_operations'])

    return success_response({'msg': 'Bulk operations saved successfully'})


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def get_bulk_operations(request, item_id):
    """
    Get bulk operations for an import queue item.
    """
    import_item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)
    bulk_ops = import_item.bulk_operations or {}
    return success_response({'bulk_operations': bulk_ops})


@api_or_login_required_401()
@require_http_methods(["PUT", "PATCH"])
@handle_404
@validate_payload(SkipStatePayload)
def save_skip_state(request, item_id, validated_data):
    """
    Save skip state (skipped feature IDs) for an import queue item.
    """
    import_item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    # Prevent updating items that have already been imported
    if import_item.imported:
        return error_response(
            'Cannot update skip state for items that have already been imported',
            code=400
        )

    skipped_feature_ids = validated_data.get('skipped_feature_ids', [])

    # Validate that all feature IDs exist in the item's geofeatures
    if skipped_feature_ids:
        # Get all feature IDs from geofeatures
        existing_feature_ids = set()
        for feature in import_item.geofeatures:
            geojson_hash = generate_geojson_hash(feature)
            existing_feature_ids.add(geojson_hash)
            # Also check if feature has an id property
            if feature.get('properties', {}).get('geojson_hash'):
                existing_feature_ids.add(feature.get('properties', {}).get('geojson_hash'))

        # Validate all skipped IDs exist
        invalid_ids = [fid for fid in skipped_feature_ids if fid not in existing_feature_ids]
        if invalid_ids:
            return error_response(
                f'Invalid feature IDs: {invalid_ids}',
                code=400
            )

    # Save skip state to the import queue item
    import_item.skipped_feature_ids = skipped_feature_ids
    import_item.save(update_fields=['skipped_feature_ids'])

    return success_response({'msg': 'Skip state saved successfully'})
