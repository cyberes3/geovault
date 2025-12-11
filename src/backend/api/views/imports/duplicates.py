"""Import duplicate detection operations"""
import time

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from django.views.decorators.http import require_http_methods

from api.models import ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response, server_error_response, handle_404
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.duplicate_detection.duplicate_detection import (
    find_duplicates_for_source,
    get_skipped_feature_ids_from_duplicates
)
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType
from geo_lib.processing.logging import DatabaseLogLevel, RealTimeImportLog
from geo_lib.website.auth import api_or_login_required_401

logger = get_tagged_logger('access')


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
            "Starting duplicate re-check against existing features in your library and other items in your import queue",
            "Duplicate Recheck",
            DatabaseLogLevel.INFO
        )

        # Perform 2-pass duplicate detection (feature store first, then cross-queue)
        start_time = time.time()
        
        # PASS 1: Check feature store (hash + geometry with hash priority)
        remaining_after_fs, feature_store_duplicates, fs_log = find_duplicates_for_source(
            features,
            request.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        realtime_log.extend(fs_log)
        
        # PASS 2: Check cross-queue (hash + geometry with hash priority) on remaining features
        remaining_after_cq, cross_queue_duplicates, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            request.user.id,
            source='cross_queue',
            exclude_queue_id=import_item.id,
            exclude_timestamp=import_item.timestamp
        )
        realtime_log.extend(cq_log)
        
        # Combine all duplicates in priority order
        duplicate_features = feature_store_duplicates + cross_queue_duplicates
        
        duration = time.time() - start_time
        realtime_log.add_timing("Duplicate re-check", duration, "Duplicate Recheck")

        # Update the import queue item with new duplicate information
        import_item.duplicate_features = duplicate_features
        
        # Auto-skip ONLY geometry duplicates by adding their feature IDs to skipped_feature_ids
        # Hash duplicates are always blocked, not added to skipped_feature_ids
        # Filter to only geometry duplicates
        geometry_duplicates = [
            dup for dup in duplicate_features 
            if dup.get('match_type') == DuplicateMatchType.GEOMETRY
        ]
        
        skipped_feature_ids = get_skipped_feature_ids_from_duplicates(
            geometry_duplicates,
            import_item.skipped_feature_ids
        )
        
        import_item.skipped_feature_ids = list(skipped_feature_ids)
        import_item.save(update_fields=['duplicate_features', 'skipped_feature_ids'])

        # Log completion
        duplicate_count = len(duplicate_features)
        realtime_log.add(
            f"Duplicate re-check completed. Found {duplicate_count} duplicate(s)",
            "Duplicate Recheck",
            DatabaseLogLevel.INFO
        )

        # Broadcast a refresh message to any connected WebSocket clients
        # so they update their duplicate markers
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
