import copy
import json
import traceback

from django import forms
from django.db import transaction
from django.http import HttpResponse, JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import (
    error_response,
    success_response,
    not_found_response,
    forbidden_response,
    server_error_response,
    handle_404,
)
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, prepare_user_tags
from geo_lib.logging.console import get_access_logger
from geo_lib.processing.jobs import process_job, delete_job, import_job
from geo_lib.processing.import_utils import validate_bulk_operations_payload
from geo_lib.processing.status_tracker import status_tracker
from geo_lib.security.file_validation import basic_file_security_check
from geo_lib.validation.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import api_or_login_required_401
from api.validation.feature_updates import validate_payload, validate_pydantic_model, FeatureUpdatePayload, ImportToFeaturestorePayload

logger = get_access_logger()


class DocumentForm(forms.Form):
    file = forms.FileField()


@api_or_login_required_401()
def upload_item(request):
    """
    Main upload endpoint - now uses async processing by default.
    """
    if request.method == 'POST':
        form = DocumentForm(request.POST, request.FILES)
        if form.is_valid():
            uploaded_file = request.FILES['file']
            file_name = uploaded_file.name

            # Basic security checks for quick rejection (full validation happens in async processing)
            is_valid, validation_message = basic_file_security_check(uploaded_file)

            if not is_valid:
                logger.warning(f"Basic security check failed for {file_name}: {validation_message}")
                return error_response(
                    f'File validation failed: {validation_message}',
                    code=400,
                    details={'job_id': None}
                )

            # Read file data after basic security check
            file_data = uploaded_file.read()

            # Get optional replacement parameter (feature ID being updated)
            replacement_feature_id = None
            if 'replacement' in request.POST:
                try:
                    replacement_feature_id = int(request.POST['replacement'])
                except (ValueError, TypeError):
                    return error_response(
                        'Invalid replacement feature ID',
                        code=400,
                        details={'job_id': None}
                    )

            # Create a processing job
            job_id = status_tracker.create_job(file_name, request.user.id)

            # Start background processing
            if process_job.start_process_job(job_id, file_data, file_name, request.user.id, replacement_feature_id=replacement_feature_id):
                return success_response({
                    'msg': 'File uploaded successfully, processing started',
                    'job_id': job_id
                })
            else:
                return server_error_response('Failed to start file processing')
        else:
            # Try to get filename even if form validation failed
            filename = "unknown file"
            if 'file' in request.FILES:
                filename = request.FILES['file'].name
            return error_response(
                f'Invalid upload structure for file "{filename}"',
                code=400,
                details={'job_id': None}
            )
    else:
        return HttpResponse(status=405)


@api_or_login_required_401()
def get_processing_status(request, job_id):
    """
    Get the processing status of a file processing job.
    """
    if not job_id:
        return error_response('Job ID not provided', code=400)

    # Get job status
    job_status = status_tracker.get_job_status(job_id)

    if not job_status:
        return not_found_response('Job not found')

    # Check if user owns this job
    job = status_tracker.get_job(job_id)
    if not job or job.user_id != request.user.id:
        return not_found_response('Job not found')  # Don't reveal existence

    return success_response({'job_status': job_status})


@api_or_login_required_401()
def get_user_processing_jobs(request):
    """
    Get all processing jobs for the current user.
    """
    user_jobs = status_tracker.get_user_jobs(request.user.id)

    job_statuses = []
    for job in user_jobs:
        job_status = status_tracker.get_job_status(job.job_id)
        if job_status:
            job_statuses.append(job_status)

    return success_response({'jobs': job_statuses})


@api_or_login_required_401()
@handle_404
def fetch_import_history_item(request, item_id: int):
    item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    response = HttpResponse(item.raw_file, content_type='application/octet-stream')
    response['Content-Disposition'] = 'attachment; filename="%s"' % item.original_filename
    return response


@api_or_login_required_401()
@handle_404
def get_import_queue_item_features(request, item_id: int):
    """
    Get the processed features (geofeatures) from an import queue item.
    Used for replacement uploads to display features for selection.
    """
    item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    return success_response({
        'geofeatures': item.geofeatures,
        'original_filename': item.original_filename,
        'imported': item.imported,
        'replacement': item.replacement
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def search_import_item_features(request, item_id: int):
    """
    Search through all features in an import queue item by name or description.
    Returns matching features with their global index and page number.
    """
    # Validate query parameter
    query = request.GET.get('query', '').strip()
    if not query:
        return error_response('query parameter is required', code=400)

    # Get import queue item
    item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    # Validate geofeatures
    geofeatures = item.geofeatures
    if not isinstance(geofeatures, list):
        return JsonResponse({
            'matches': [],
            'total_matches': 0
        })

    # Search configuration
    PAGE_SIZE = 50
    MAX_RESULTS = 150
    query_lower = query.lower()

    # Search through all features
    matches = []
    total_matches = 0

    for feature_index, feature in enumerate(geofeatures):
        if not isinstance(feature, dict):
            continue

        properties = feature.get('properties', {})
        if not isinstance(properties, dict):
            continue

        # Check if feature matches query
        name = (properties.get('name') or '').lower()
        description = (properties.get('description') or '').lower()

        if query_lower in name or query_lower in description:
            total_matches += 1

            # Only include in results if under limit
            if len(matches) < MAX_RESULTS:
                page = (feature_index // PAGE_SIZE) + 1
                matches.append({
                    'feature_index': feature_index,
                    'page': page,
                    'feature': feature
                })

    return success_response({
        'matches': matches,
        'total_matches': total_matches
    })


@api_or_login_required_401()
@handle_404
def delete_import_item(request, id):
    if request.method == 'DELETE':
        queue = get_object_or_404_for_user(ImportQueue, request.user, id=id)

        # Start async delete job
        job_id = delete_job.start_delete_job(id, request.user.id, queue.original_filename)

        if job_id:
            return success_response({
                'msg': 'Delete job started',
                'job_id': job_id
            })
        else:
            return server_error_response('Failed to start delete job')
    return HttpResponse(status=405)


@api_or_login_required_401()
@require_http_methods(["PUT", "PATCH"])
@validate_payload(FeatureUpdatePayload)
@handle_404
def update_import_item(request, item_id, validated_data):
    queue = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    # Prevent updating items that have already been imported to the feature store
    if queue.imported:
        return error_response(
            'Cannot update items that have already been imported to the feature store',
            code=400
        )

    # Build a lookup map of feature ID to partial update fields
    updates_by_id = {}
    features_to_update = validated_data['features']
    allowed_fields = {'name', 'description', 'created', 'tags'}

    for feature in features_to_update:
        # Extract properties from the validated feature
        properties = feature.get('properties', {})
        if not properties:
            continue

        # Extract feature ID for matching (id is not an updatable field)
        feature_id = properties.get('id')
        if not feature_id:
            logger.warning(f"Skipping feature without ID: {properties.get('name', 'Unnamed')}")
            continue

        # Extract only allowed updatable fields (name, description, created, tags)
        update_fields = {}
        for field in allowed_fields:
            if field in properties:
                update_fields[field] = properties[field]

        # Validate that at least one field is being updated
        if not update_fields:
            logger.warning(f"Skipping feature {feature_id}: no updatable fields provided")
            continue

        updates_by_id[feature_id] = update_fields

    # Update features in the geofeatures array by matching IDs
    # Wrap in transaction to ensure atomicity
    updated_count = 0
    with transaction.atomic():
        for i, existing_feature in enumerate(queue.geofeatures):
            feature_id = existing_feature.get('properties', {}).get('id')
            if feature_id and feature_id in updates_by_id:
                # Create a deep copy of the original feature to merge updates into
                merged_feature = copy.deepcopy(existing_feature)

                # Preserve existing system_tags from original feature
                from api.views.feature_update import _extract_system_tags
                original_system_tags = _extract_system_tags(existing_feature)

                # Get the partial update fields
                update_fields = updates_by_id[feature_id]

                # Merge update fields into the feature properties (only update fields that are present)
                merged_feature.setdefault('properties', {})

                for field, value in update_fields.items():
                    if field == 'tags':
                        user_tags = filter_protected_tags(value, CONST_INTERNAL_TAGS)
                        user_tags = prepare_user_tags(user_tags)
                        merged_feature['properties']['tags'] = user_tags
                    else:
                        merged_feature['properties'][field] = value

                # Ensure the feature has the required structure (type, geometry, properties)
                if 'type' not in merged_feature:
                    merged_feature['type'] = 'Feature'
                if 'geometry' not in merged_feature:
                    merged_feature['geometry'] = existing_feature.get('geometry', {})

                # Run the merged feature through validate_and_normalize_geojson_feature()
                try:
                    normalized_feature = validate_and_normalize_geojson_feature(
                        merged_feature,
                        preserve_system_tags=original_system_tags,
                        preserve_id=False
                    )
                except GeometryValidationError as e:
                    logger.warning(f"Error validating feature {feature_id} during update: {str(e)}")
                    continue

                # Ensure system_tags are preserved after normalization
                normalized_feature['properties']['system_tags'] = original_system_tags

                queue.geofeatures[i] = normalized_feature
                updated_count += 1

        # Save the updated queue
        queue.save()

    return success_response({
        'msg': f'Successfully updated {updated_count} feature(s)',
        'updated_count': updated_count
    })


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(ImportToFeaturestorePayload, allow_empty=True)
@handle_404
def import_to_featurestore(request, item_id, validated_data):
    """
    Start async import job for importing an import queue item to the feature store.
    All processing happens in the async ImportJob.
    """
    import_item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    # Prevent importing items that have already been imported to the feature store
    if import_item.imported:
        return error_response(
            'This item has already been imported to the feature store',
            code=400
        )

    # Get import_custom_icons flag and skipped_feature_ids from validated data
    import_custom_icons = validated_data.get('import_custom_icons', True)
    skipped_feature_ids = validated_data.get('skipped_feature_ids', [])

    # Check for file-level duplicates before importing
    # Only block duplicates that are still in the queue (not yet imported)
    # Allow re-importing files that were previously imported
    if import_item.geojson_hash:
        # Check if there are other items in queue with same hash (uploaded earlier)
        earlier_duplicates = ImportQueue.objects.filter(
            user=request.user,
            geojson_hash=import_item.geojson_hash,
            imported=False,
            timestamp__lt=import_item.timestamp
        ).order_by('timestamp').first()

        if earlier_duplicates:
            return error_response(
                f'This file is a duplicate of "{earlier_duplicates.original_filename}" which is already in the import queue',
                code=409
            )

    # Start async import job - all processing happens there
    job_id = import_job.start_import_job(
        item_id=item_id,
        user_id=request.user.id,
        import_custom_icons=import_custom_icons,
        skipped_feature_ids=skipped_feature_ids
    )

    # Return immediately - completion status will be sent via WebSocket
    return success_response({
        'msg': 'Import job started',
        'job_id': job_id,
        'item_id': item_id
    })


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

    except (json.JSONDecodeError, ValueError) as e:
        return error_response(str(e), code=400)
    except Exception as e:
        logger.error(f"Error saving bulk operations: {str(e)}")
        return server_error_response('Failed to save bulk operations')


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def get_bulk_operations(request, item_id):
    """
    Get bulk operations for an import queue item.
    """
    import_item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    # Return bulk operations (default to empty dict if None)
    bulk_ops = import_item.bulk_operations or {}
    return success_response({'bulk_operations': bulk_ops})


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
def recheck_duplicates(request, item_id):
    """
    Re-run coordinate duplicate detection for an import queue item.
    This is useful when other features may have been imported after the file was initially uploaded.
    """
    import_item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    # Prevent rechecking duplicates for items that have already been imported
    if import_item.imported:
        return error_response(
            'Cannot recheck duplicates for items that have already been imported',
            code=400
        )

    try:
        from geo_lib.processing.duplicate_detection import find_coordinate_duplicates
        from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
        import time

        # Get the features from the import item
        features = import_item.geofeatures
        if not features:
            return success_response({
                'msg': 'No features to check',
                'duplicate_count': 0
            })

        # Create a real-time log for this operation
        realtime_log = RealTimeImportLog(user_id=request.user.id, log_id=import_item.log_id)
        
        # Log the start of the manual recheck operation
        realtime_log.add(
            "Manual duplicate re-check requested by user",
            "Duplicate Recheck",
            DatabaseLogLevel.INFO
        )
        realtime_log.add(
            "Starting duplicate re-check against existing features in your library",
            "Duplicate Recheck",
            DatabaseLogLevel.INFO
        )

        # Perform duplicate detection
        start_time = time.time()
        unique_features, duplicate_features, duplicate_log = find_coordinate_duplicates(
            features,
            request.user.id
        )
        duration = time.time() - start_time

        # Extend the real-time log with duplicate detection results
        realtime_log.extend(duplicate_log)
        realtime_log.add_timing("Duplicate re-check", duration, "Duplicate Recheck")

        # Update the import queue item with new duplicate information
        import_item.duplicate_features = duplicate_features
        import_item.save(update_fields=['duplicate_features'])

        # Log completion
        duplicate_count = len(duplicate_features)
        realtime_log.add(
            f"Duplicate re-check completed. Found {duplicate_count} duplicate(s)",
            "Duplicate Recheck",
            DatabaseLogLevel.INFO
        )

        # Broadcast a refresh message to any connected WebSocket clients
        # so they update their duplicate markers
        from channels.layers import get_channel_layer
        from asgiref.sync import async_to_sync
        
        channel_layer = get_channel_layer()
        if channel_layer:
            # Send a message to trigger the process_status module to refresh the page data
            async_to_sync(channel_layer.group_send)(
                f"process_status_{request.user.id}_{import_item.id}",
                {
                    'type': 'duplicates_updated',
                    'data': {}
                }
            )

        return success_response({
            'msg': 'Duplicates rechecked successfully',
            'duplicate_count': duplicate_count
        })

    except Exception as e:
        logger.error(f"Error rechecking duplicates for item {item_id}: {str(e)}")
        return server_error_response('Failed to recheck duplicates')
