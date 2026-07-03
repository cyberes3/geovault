"""
CalTopo maps and features endpoints.
"""
from django.http import HttpRequest, JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import ImportQueue, FeatureStore
from api.utils.responses import success_response, not_found_response
from extensions.caltopo.src.backend.services.caltopo_api import list_maps, get_map_features
from extensions.caltopo.src.backend.utils.caltopo_helpers import require_caltopo_connection, perform_caltopo_call, is_valid_caltopo_feature_class
from extensions.caltopo.src.backend.utils.rate_limit import caltopo_rate_limiter
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker, ProcessingStatus
from geo_lib.website.auth import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["GET"])
@caltopo_rate_limiter()
def list_caltopo_maps(request: HttpRequest) -> JsonResponse:
    """
    List all available CalTopo maps for the current user.
    
    GET /api/extensions/caltopo/maps/
    """
    caltopo_user, error_resp = require_caltopo_connection(request)
    if error_resp:
        return error_resp

    maps, error_resp = perform_caltopo_call(list_maps, request.user)
    if error_resp:
        return error_resp

    return success_response({
        'maps': maps,
        'count': len(maps)
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
@caltopo_rate_limiter()
def get_caltopo_map_features(request: HttpRequest, map_id: str) -> JsonResponse:
    """
    Get all features from a specific CalTopo map.
    
    GET /api/extensions/caltopo/maps/{map_id}/features/
    """
    caltopo_user, error_resp = require_caltopo_connection(request)
    if error_resp:
        return error_resp

    features, error_resp = perform_caltopo_call(get_map_features, request.user, map_id)
    if error_resp:
        return error_resp

    if features is None:
        return not_found_response(f'Map {map_id} not found or access denied')

    # Check if this map is already in the import queue with various statuses
    filename = f'caltopo_map_{map_id}.geojson'

    # Check for items in different states: done (imported=True), failed (unparsable=True), 
    # queued/processing/failed (in status_tracker)
    queue_item_done = ImportQueue.objects.filter(
        user=request.user,
        original_filename=filename,
        imported=True
    ).first()

    queue_item_failed = ImportQueue.objects.filter(
        user=request.user,
        original_filename=filename,
        unparsable=True
    ).first()

    queue_item_active = ImportQueue.objects.filter(
        user=request.user,
        original_filename=filename,
        imported=False,
        unparsable=False
    ).first()

    # Check status_tracker for queued, processing, or failed jobs
    queue_status = None
    import_queue_id = None

    if queue_item_done:
        queue_status = 'done'
        import_queue_id = queue_item_done.id
    elif queue_item_failed:
        queue_status = 'failed'
        import_queue_id = queue_item_failed.id
    elif queue_item_active:
        import_queue_id = queue_item_active.id
        # Check status_tracker for this import_queue_id
        user_jobs = status_tracker.get_user_jobs(request.user.id)
        for job in user_jobs:
            if job.import_queue_id == import_queue_id:
                if job.status == ProcessingStatus.QUEUED:
                    queue_status = 'queued'
                elif job.status == ProcessingStatus.PROCESSING:
                    queue_status = 'processing'
                elif job.status == ProcessingStatus.FAILED:
                    queue_status = 'failed'
                break

        # If no job found in tracker, item is in queue but not yet processed
        if queue_status is None:
            queue_status = 'queued'

    is_in_queue = queue_status is not None

    # Check which features have already been imported
    # We need to verify that the feature actually exists in FeatureStore,
    # not just that it's in the mapping (in case user deleted it)

    # Get all feature IDs that are mapped for this map
    mapped_feature_ids = {}
    if map_id in caltopo_user.imported_features:
        mapped_feature_ids = caltopo_user.imported_features[map_id]

    # Verify which mapped features actually exist in FeatureStore
    existing_feature_store_ids = set()
    if mapped_feature_ids:
        existing_feature_store_ids = set(
            FeatureStore.objects.filter(
                user=request.user,
                id__in=mapped_feature_ids.values()
            ).values_list('id', flat=True)
        )

    # Build a map of CalTopo feature IDs to database IDs for imported features
    # Also clean up stale mappings for features that no longer exist
    imported_caltopo_feature_ids = set()
    caltopo_to_database_id = {}
    needs_cleanup = False
    cleaned_mapping = mapped_feature_ids.copy()

    for caltopo_feature_id, feature_store_id in mapped_feature_ids.items():
        if feature_store_id in existing_feature_store_ids:
            imported_caltopo_feature_ids.add(caltopo_feature_id)
            caltopo_to_database_id[caltopo_feature_id] = feature_store_id
        else:
            # Feature was deleted, remove from mapping
            cleaned_mapping.pop(caltopo_feature_id, None)
            needs_cleanup = True

    # Update the mapping if we found stale entries
    if needs_cleanup:
        caltopo_user.imported_features[map_id] = cleaned_mapping
        caltopo_user.save(update_fields=['imported_features'])

    # Add import status, database_id, and validity to each feature
    features_with_status = []
    for feature in features:
        feature_id = feature.get('id', '')
        is_imported = feature_id in imported_caltopo_feature_ids
        feature_class = feature.get('properties', {}).get('class', '')
        is_valid = is_valid_caltopo_feature_class(feature_class) if feature_class else True

        feature_with_status = feature.copy()
        feature_with_status['is_imported'] = is_imported
        feature_with_status['is_valid'] = is_valid
        # Include database_id if feature is imported (for navigation)
        if is_imported and feature_id in caltopo_to_database_id:
            feature_with_status['database_id'] = caltopo_to_database_id[feature_id]
        features_with_status.append(feature_with_status)

    return success_response({
        'map_id': map_id,
        'features': features_with_status,
        'count': len(features_with_status),
        'is_in_queue': is_in_queue,
        'import_queue_id': import_queue_id,
        'queue_status': queue_status
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
@caltopo_rate_limiter()
def get_caltopo_map_details(request: HttpRequest, map_id: str) -> JsonResponse:
    """
    Get details about a specific CalTopo map.
    
    GET /api/extensions/caltopo/maps/{map_id}/
    """
    caltopo_user, error_resp = require_caltopo_connection(request)
    if error_resp:
        return error_resp

    maps, error_resp = perform_caltopo_call(list_maps, request.user)
    if error_resp:
        return error_resp

    # Find the map
    map_details = None
    for map_item in maps:
        if map_item.get('id') == map_id:
            map_details = map_item
            break

    if not map_details:
        return not_found_response(f'Map {map_id} not found')

    return success_response({
        'map': map_details
    })
