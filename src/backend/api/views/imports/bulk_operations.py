"""Import bulk operations management"""
import json
import traceback

from django.views.decorators.http import require_http_methods

from api.models import ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response, server_error_response, handle_404
from api.validation.bulk_opts import validate_bulk_operations_payload
from api.validation.feature_updates import validate_payload, SkipStatePayload
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["PUT", "PATCH"])
@handle_404
def save_bulk_operations(request, item_id):
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

    try:
        data = json.loads(request.body)
        if not isinstance(data, dict):
            raise ValueError('Invalid data format. Expected a JSON object.')

        # Validate bulk operations structure
        bulk_ops = data.get('bulk_operations', {})
        if not isinstance(bulk_ops, dict):
            raise ValueError('bulk_operations must be a JSON object')

        # Validate payload using shared helper
        is_valid, error_message = validate_bulk_operations_payload(bulk_ops)
        if not is_valid:
            raise ValueError(error_message or 'Invalid bulk_operations payload')

        # Save bulk operations to the import queue item
        import_item.bulk_operations = bulk_ops
        import_item.save(update_fields=['bulk_operations'])

        return success_response({'msg': 'Bulk operations saved successfully'})

    except (json.JSONDecodeError, ValueError):
        return error_response('Invalid request data', code=400)


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
