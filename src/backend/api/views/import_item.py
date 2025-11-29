import copy
import json
import traceback

from django import forms
from django.http import HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods
import time

from api.models import ImportQueue
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, prepare_user_tags
from geo_lib.logging.console import get_access_logger
from geo_lib.processing.jobs import process_job, delete_job, import_job
from geo_lib.processing.status_tracker import status_tracker
from geo_lib.security.file_validation import basic_file_security_check
from geo_lib.validation.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


class DocumentForm(forms.Form):
    file = forms.FileField()


@login_required_401
@csrf_protect
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
                return JsonResponse({
                    'msg': f'File validation failed: {validation_message}',
                    'job_id': None
                }, status=400)

            # Read file data after basic security check
            file_data = uploaded_file.read()

            # Get optional replacement parameter (feature ID being updated)
            replacement_feature_id = None
            if 'replacement' in request.POST:
                try:
                    replacement_feature_id = int(request.POST['replacement'])
                except (ValueError, TypeError):
                    return JsonResponse({
                        'msg': 'Invalid replacement feature ID',
                        'job_id': None
                    }, status=400)

            # Create a processing job
            job_id = status_tracker.create_job(file_name, request.user.id)

            # Start background processing
            if process_job.start_process_job(job_id, file_data, file_name, request.user.id, replacement_feature_id=replacement_feature_id):
                return JsonResponse({
                    'msg': 'File uploaded successfully, processing started',
                    'job_id': job_id
                }, status=200)
            else:
                return JsonResponse({
                    'msg': 'Failed to start file processing',
                    'job_id': None
                }, status=500)
        else:
            # Try to get filename even if form validation failed
            filename = "unknown file"
            if 'file' in request.FILES:
                filename = request.FILES['file'].name
            return JsonResponse({
                'msg': f'Invalid upload structure for file "{filename}"',
                'job_id': None
            }, status=400)
    else:
        return HttpResponse(status=405)


@login_required_401
def get_processing_status(request, job_id):
    """
    Get the processing status of a file processing job.
    """
    if not job_id:
        return JsonResponse({'msg': 'Job ID not provided'}, status=400)

    # Get job status
    job_status = status_tracker.get_job_status(job_id)

    if not job_status:
        return JsonResponse({'msg': 'Job not found'}, status=404)

    # Check if user owns this job
    job = status_tracker.get_job(job_id)
    if not job or job.user_id != request.user.id:
        return JsonResponse({'msg': 'Not authorized to view this job'}, status=403)

    return JsonResponse({
        'job_status': job_status
    }, status=200)


@login_required_401
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

    return JsonResponse({
        'jobs': job_statuses
    }, status=200)


@login_required_401
def fetch_import_history_item(request, item_id: int):
    item = ImportQueue.objects.get(id=item_id)
    if item.user_id != request.user.id:
        return JsonResponse({'msg': 'not authorized to view this item', 'code': 403}, status=400)

    response = HttpResponse(item.raw_file, content_type='application/octet-stream')
    response['Content-Disposition'] = 'attachment; filename="%s"' % item.original_filename
    return response


@login_required_401
def get_import_queue_item_features(request, item_id: int):
    """
    Get the processed features (geofeatures) from an import queue item.
    Used for replacement uploads to display features for selection.
    """
    try:
        item = ImportQueue.objects.get(id=item_id, user=request.user)

        return JsonResponse({
            'geofeatures': item.geofeatures,
            'original_filename': item.original_filename,
            'imported': item.imported,
            'replacement': item.replacement
        })
    except ImportQueue.DoesNotExist:
        return JsonResponse({
            'error': 'Import queue item not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error fetching import queue item features: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to fetch features',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def search_import_item_features(request, item_id: int):
    """
    Search through all features in an import queue item by name or description.
    Returns matching features with their global index and page number.
    """
    try:
        # Validate query parameter
        query = request.GET.get('query', '').strip()
        if not query:
            return JsonResponse({
                'error': 'query parameter is required',
                'code': 400
            }, status=400)

        # Get import queue item
        try:
            item = ImportQueue.objects.get(id=item_id, user=request.user)
        except ImportQueue.DoesNotExist:
            return JsonResponse({
                'error': 'Import queue item not found or access denied',
                'code': 404
            }, status=404)

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

        return JsonResponse({
            'matches': matches,
            'total_matches': total_matches
        })

    except Exception as e:
        logger.error(f"Error searching import item features: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to search features',
            'code': 500
        }, status=500)


@login_required_401
@csrf_protect
def delete_import_item(request, id):
    if request.method == 'DELETE':
        try:
            queue = ImportQueue.objects.get(id=id)
        except ImportQueue.DoesNotExist:
            return JsonResponse({'msg': 'ID does not exist', 'code': 404}, status=400)

        # Check if user owns this item
        if queue.user_id != request.user.id:
            return JsonResponse({'msg': 'Not authorized to delete this item', 'code': 403}, status=400)

        # Start async delete job
        job_id = delete_job.start_delete_job(id, request.user.id, queue.original_filename)

        if job_id:
            return JsonResponse({
                'msg': 'Delete job started',
                'job_id': job_id
            })
        else:
            return JsonResponse({
                'msg': 'Failed to start delete job'
            }, status=500)
    return HttpResponse(status=405)


@login_required_401
@csrf_protect
@require_http_methods(["PUT", "PATCH"])
def update_import_item(request, item_id):
    try:
        queue = ImportQueue.objects.get(id=item_id)
    except ImportQueue.DoesNotExist:
        return JsonResponse({'msg': 'ID does not exist', 'code': 404}, status=400)
    if queue.user_id != request.user.id:
        return JsonResponse({'msg': 'not authorized to edit this item', 'code': 403}, status=403)

    # Prevent updating items that have already been imported to the feature store
    if queue.imported:
        return JsonResponse({
            'msg': 'Cannot update items that have already been imported to the feature store',
            'code': 400
        }, status=400)

    try:
        data = json.loads(request.body)
        if not isinstance(data, dict) or 'features' not in data:
            raise ValueError('Invalid data format. Expected {"features": [{"properties": {"id": "...", ...}}, ...]}')

        features_to_update = data['features']
        if not isinstance(features_to_update, list):
            raise ValueError('features must be a list')
    except (json.JSONDecodeError, ValueError) as e:
        return JsonResponse({'msg': str(e), 'code': 400}, status=400)

    # Build a lookup map of feature ID to partial update fields
    updates_by_id = {}
    allowed_fields = {'name', 'description', 'created', 'tags'}

    for feature in features_to_update:
        # Extract properties from the feature
        properties = feature.get('properties', {})
        if not isinstance(properties, dict):
            logger.warning(f"Skipping feature with invalid properties: {feature}")
            continue

        feature_id = properties.get('id')
        if not feature_id:
            logger.warning(f"Skipping feature without ID: {properties.get('name', 'Unnamed')}")
            continue

        # Extract only allowed fields (name, description, created, tags)
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
    updated_count = 0
    for i, existing_feature in enumerate(queue.geofeatures):
        feature_id = existing_feature.get('properties', {}).get('id')
        if feature_id and feature_id in updates_by_id:
            # Create a deep copy of the original feature to merge updates into
            merged_feature = copy.deepcopy(existing_feature)

            # Preserve existing system_tags from original feature
            original_system_tags = existing_feature.get('properties', {}).get('system_tags', [])
            if not isinstance(original_system_tags, list):
                original_system_tags = []

            # Get the partial update fields
            update_fields = updates_by_id[feature_id]

            # Merge update fields into the feature properties (only update fields that are present)
            merged_feature.setdefault('properties', {})

            for field, value in update_fields.items():
                if field == 'tags':
                    # Handle tags specially - filter out system tags and prepare user tags
                    if not isinstance(value, list):
                        value = []
                    # Filter out any system tags that user might have added
                    user_tags = filter_protected_tags(value, CONST_INTERNAL_TAGS)
                    # Prepare user tags (lowercase and deduplicate)
                    user_tags = prepare_user_tags(user_tags)
                    merged_feature['properties']['tags'] = user_tags
                else:
                    # For name, description, created - update directly
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

    return JsonResponse({
        'msg': f'Successfully updated {updated_count} feature(s)',
        'updated_count': updated_count
    })


@login_required_401
@csrf_protect
@require_http_methods(["POST"])
def import_to_featurestore(request, item_id):
    """
    Start async import job for importing an import queue item to the feature store.
    All processing happens in the async ImportJob.
    """
    try:
        import_item = ImportQueue.objects.get(id=item_id)
    except ImportQueue.DoesNotExist:
        return JsonResponse({'msg': 'ID does not exist', 'code': 404}, status=400)
    if import_item.user_id != request.user.id:
        return JsonResponse({'msg': 'not authorized to edit this item', 'code': 403}, status=403)

    # Prevent importing items that have already been imported to the feature store
    if import_item.imported:
        return JsonResponse({
            'msg': 'This item has already been imported to the feature store',
            'code': 400
        }, status=400)

    # Parse request body to get import_custom_icons flag and skipped_feature_ids
    import_custom_icons = True  # Default to True for backward compatibility
    skipped_feature_ids = []  # List of feature IDs to skip during import
    try:
        if request.body:
            data = json.loads(request.body)
            if isinstance(data, dict):
                import_custom_icons = data.get('import_custom_icons', True)
                # Parse skipped_feature_ids if provided
                skipped_ids = data.get('skipped_feature_ids', [])
                if isinstance(skipped_ids, list):
                    skipped_feature_ids = skipped_ids
    except (json.JSONDecodeError, ValueError) as e:
        logger.warning(f"Failed to parse request body: {str(e)}, using defaults")

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
            return JsonResponse({
                'msg': f'This file is a duplicate of "{earlier_duplicates.original_filename}" which is already in the import queue',
                'code': 409
            }, status=409)

    # Start async import job - all processing happens there
    job_id = import_job.start_import_job(
        item_id=item_id,
        user_id=request.user.id,
        import_custom_icons=import_custom_icons,
        skipped_feature_ids=skipped_feature_ids
    )

    # Check if the caller requested blocking behavior
    blocking = request.GET.get('blocking', 'false').lower() == 'true'

    if not blocking:
        # Default behavior: return immediately and let the job run in background
        return JsonResponse({
            'msg': 'Import job started',
            'job_id': job_id
        }, status=200)

    # Blocking behavior: wait for the job to finish before returning a response
    timeout_seconds = 300  # 5 minutes
    poll_interval = 0.1
    start_time = time.time()

    while True:
        # Check for timeout
        if time.time() - start_time > timeout_seconds:
            return JsonResponse({
                'msg': 'Import job timed out while waiting for completion',
                'job_id': job_id,
                'code': 504
            }, status=504)

        job_status = status_tracker.get_job_status(job_id)

        # If job no longer exists, return an error
        if not job_status:
            return JsonResponse({
                'msg': 'Import job not found',
                'job_id': job_id,
                'code': 404
            }, status=404)

        status = job_status.get('status')

        # Terminal states: completed, failed, or cancelled
        if status in ('completed', 'failed', 'cancelled'):
            # Refresh import item to get latest state
            import_item.refresh_from_db()

            response_payload = {
                'msg': job_status.get('message', ''),
                'job_id': job_id,
                'job_status': job_status,
                'imported': import_item.imported
            }

            if status == 'completed':
                return JsonResponse(response_payload, status=200)
            elif status == 'failed':
                response_payload['code'] = 500
                return JsonResponse(response_payload, status=500)
            else:  # cancelled
                response_payload['code'] = 499  # client closed request / cancelled
                return JsonResponse(response_payload, status=499)

        # Not finished yet, wait a bit before polling again
        time.sleep(poll_interval)


@login_required_401
@csrf_protect
@require_http_methods(["PUT", "PATCH"])
def save_bulk_operations(request, item_id):
    """
    Save bulk operations (tags, styling) for an import queue item.
    These operations will be applied during import.
    """
    try:
        import_item = ImportQueue.objects.get(id=item_id)
    except ImportQueue.DoesNotExist:
        return JsonResponse({'msg': 'ID does not exist', 'code': 404}, status=400)
    if import_item.user_id != request.user.id:
        return JsonResponse({'msg': 'not authorized to edit this item', 'code': 403}, status=403)

    # Prevent updating items that have already been imported
    if import_item.imported:
        return JsonResponse({
            'msg': 'Cannot update bulk operations for items that have already been imported',
            'code': 400
        }, status=400)

    try:
        data = json.loads(request.body)
        if not isinstance(data, dict):
            raise ValueError('Invalid data format. Expected a JSON object.')

        # Validate bulk operations structure
        bulk_ops = data.get('bulk_operations', {})
        if not isinstance(bulk_ops, dict):
            raise ValueError('bulk_operations must be a JSON object')

        # Save bulk operations to the import queue item
        import_item.bulk_operations = bulk_ops
        import_item.save(update_fields=['bulk_operations'])

        return JsonResponse({
            'msg': 'Bulk operations saved successfully'
        }, status=200)

    except (json.JSONDecodeError, ValueError) as e:
        return JsonResponse({'msg': str(e), 'code': 400}, status=400)
    except Exception as e:
        logger.error(f"Error saving bulk operations: {str(e)}")
        return JsonResponse({
            'msg': 'Failed to save bulk operations',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def get_bulk_operations(request, item_id):
    """
    Get bulk operations for an import queue item.
    """
    try:
        import_item = ImportQueue.objects.get(id=item_id)
    except ImportQueue.DoesNotExist:
        return JsonResponse({'msg': 'ID does not exist', 'code': 404}, status=400)
    if import_item.user_id != request.user.id:
        return JsonResponse({'msg': 'not authorized to view this item', 'code': 403}, status=403)

    # Return bulk operations (default to empty dict if None)
    bulk_ops = import_item.bulk_operations or {}
    return JsonResponse({
        'bulk_operations': bulk_ops
    }, status=200)
