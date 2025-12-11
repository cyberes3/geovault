"""Import queue management operations"""
import copy

from django.db import transaction
from django.http import HttpResponse
from django.views.decorators.http import require_http_methods

from api.models import ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import (
    error_response,
    success_response,
    not_found_response,
    server_error_response,
    handle_404,
)
from api.validation.feature_updates import validate_payload, FeatureUpdatePayload, ImportToFeaturestorePayload
from api.views.features.updates.shared import extract_system_tags
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.delete_job import DeleteJob
from geo_lib.processing.jobs.helpers.redis_job_storage import get_user_jobs
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.jobs.import_job import ImportJob
from geo_lib.tags.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, prepare_user_tags
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()

# Create singleton instances
delete_job = DeleteJob(status_tracker)
import_job = ImportJob(status_tracker)


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
def get_all_job_statuses(request):
    """
    Get all background job statuses (import, delete, bulk_import, bulk_delete) for the current user from Redis.
    This endpoint allows API users to check job status without requiring WebSocket connections.
    """
    jobs = get_user_jobs(request.user.id)
    return success_response({'jobs': jobs})


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
        return success_response({
            'matches': [],
            'total_matches': 0
        })

    # Search configuration
    page_size = 50
    max_results = 150
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
            if len(matches) < max_results:
                page = (feature_index // page_size) + 1
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
@handle_404
@validate_payload(FeatureUpdatePayload)
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

        # Extract feature ID for matching (geojson_hash is not an updatable field)
        feature_id = properties.get('geojson_hash')
        if not feature_id:
            _logger.warning(f"Skipping feature without geojson_hash: {properties.get('name', 'Unnamed')}")
            continue

        # Extract only allowed updatable fields (name, description, created, tags)
        update_fields = {}
        for field in allowed_fields:
            if field in properties:
                update_fields[field] = properties[field]

        # Validate that at least one field is being updated
        if not update_fields:
            _logger.warning(f"Skipping feature {feature_id}: no updatable fields provided")
            continue

        updates_by_id[feature_id] = update_fields

    # Update features in the geofeatures array by matching IDs
    # Wrap in transaction to ensure atomicity
    updated_count = 0
    with transaction.atomic():
        for i, existing_feature in enumerate(queue.geofeatures):
            feature_id = existing_feature.get('properties', {}).get('geojson_hash')
            if feature_id and feature_id in updates_by_id:
                # Create a deep copy of the original feature to merge updates into
                merged_feature = copy.deepcopy(existing_feature)

                # Preserve existing system_tags from original feature
                original_system_tags = extract_system_tags(existing_feature)

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
                normalized_feature = validate_and_normalize_geojson_feature(
                    merged_feature,
                    preserve_system_tags=original_system_tags,
                    preserve_geojson_hash=True
                )

                assert normalized_feature['properties']['system_tags'] == original_system_tags
                assert normalized_feature['properties']['geojson_hash']

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
@handle_404
@validate_payload(ImportToFeaturestorePayload, allow_empty=True)
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
    if import_item.file_hash:
        # Check if there are other items in queue with same hash (uploaded earlier)
        earlier_duplicates = ImportQueue.objects.filter(
            user=request.user,
            file_hash=import_item.file_hash,
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
