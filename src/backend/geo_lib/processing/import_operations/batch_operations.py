"""
Batch operations utilities for import operations.
Handles batch processing, bulk creation, and import finalization.
"""

import threading
import traceback
from concurrent.futures import ThreadPoolExecutor
from typing import List, Tuple, Optional, Dict, Any

from django.db import IntegrityError

from api.models import ImportQueue, FeatureStore, DatabaseLogging
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.duplicate_detection.models import SkippedDuplicates, SkippedDuplicateFeature
from geo_lib.processing.import_operations.feature_processing import process_single_feature_for_import
from geo_lib.processing.import_operations.styling import apply_bulk_operations
from geo_lib.processing.import_operations.websocket import broadcast_item_imported
from geo_lib.utils.db_connection import ensure_db_connection_cleanup
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()


def delete_logs_by_log_id(log_id):
    """Delete all logs from DatabaseLogging table by log_id"""
    deleted_count = DatabaseLogging.objects.filter(log_id=log_id).delete()[0]
    return deleted_count


def process_features_for_import(
        import_item: ImportQueue,
        user_id: int,
        import_custom_icons: bool,
        features_to_process: Optional[List[Dict[str, Any]]] = None,
        skipped_feature_ids: Optional[set] = None
) -> Tuple[List[FeatureStore], SkippedDuplicates]:
    """
    Process features from an import item and return FeatureStore objects ready for creation.
    Shared utility used by both single and bulk import jobs.
    
    Args:
        import_item: The ImportQueue item being imported
        user_id: ID of the user importing
        import_custom_icons: Whether to import custom icons
        features_to_process: Optional list of features to process (defaults to import_item.geofeatures)
        skipped_feature_ids: Optional set of feature IDs/hashes that should be skipped (coordinate duplicates)
        
    Returns:
        Tuple of (List of FeatureStore objects ready for bulk_create, SkippedDuplicates model)
    """
    if features_to_process is None:
        features_to_process = import_item.geofeatures

    if skipped_feature_ids is None:
        skipped_feature_ids = set()

    # Setup duplicate detection
    features_to_create = []
    existing_hashes = set()
    current_batch_hashes = set()
    skipped_hash_duplicates: List[SkippedDuplicateFeature] = []
    skipped_geometry_duplicates: List[SkippedDuplicateFeature] = []

    # Build set of geometry duplicate hashes from duplicate_features
    geometry_duplicate_hashes = set()
    if import_item.duplicate_features:
        for dup_info in import_item.duplicate_features:
            dup_feature = dup_info.get('feature')
            if dup_feature:
                # Use stored hash if available (preserves original hash from processing)
                geojson_hash = dup_feature['properties'].get('geojson_hash')
                geometry_duplicate_hashes.add(geojson_hash)

    # Get existing feature hashes for this user to avoid duplicates
    existing_features = FeatureStore.objects.filter(user_id=user_id).values_list('geojson_hash', flat=True)
    existing_hashes.update(existing_features)

    # Query other unimported ImportQueue items for cross-queue duplicate detection
    # Only check against older items (by timestamp) - newer items should be marked as duplicates of older ones
    other_queue_items = ImportQueue.objects.filter(
        user_id=user_id,
        imported=False,
        timestamp__lt=import_item.timestamp  # Only older items
    ).exclude(id=import_item.id)

    # Extract feature hashes and build lookup map
    queue_hash_to_item = {}
    for queue_item in other_queue_items:
        for feature in queue_item.geofeatures:
            # Use stored hash if available (preserves original hash from processing)
            geojson_hash = feature.get('properties', {}).get('geojson_hash')
            if not geojson_hash:
                geojson_hash = generate_geojson_hash(feature)
            if geojson_hash not in queue_hash_to_item:
                queue_hash_to_item[geojson_hash] = {
                    'queue_item_id': queue_item.id,
                    'queue_item_filename': queue_item.original_filename
                }

    duplicate_check_lock = threading.Lock()

    @ensure_db_connection_cleanup
    def process_feature_with_index(args: Tuple[int, Dict[str, Any]]) -> Optional[FeatureStore]:
        """Wrapper to unpack index and feature for executor.map()"""
        feature_index, feature = args
        return process_single_feature_for_import(
            feature, feature_index, import_item, user_id, import_custom_icons,
            existing_hashes, current_batch_hashes, duplicate_check_lock,
            queue_hash_to_item, skipped_hash_duplicates,
            skipped_feature_ids, geometry_duplicate_hashes, skipped_geometry_duplicates
        )

    # Apply bulk operations
    bulk_ops = import_item.bulk_operations or {}
    if bulk_ops:
        features_to_process = apply_bulk_operations(features_to_process, bulk_ops)

    # Process features in parallel
    if len(features_to_process) > 0:
        with ThreadPoolExecutor(max_workers=get_required_setting('IMPORT_PROCESSING_THREADS')) as executor:
            results = executor.map(process_feature_with_index, enumerate(features_to_process))
            for feature_store in results:
                if feature_store is not None:
                    features_to_create.append(feature_store)

    return features_to_create, SkippedDuplicates(
        hash=skipped_hash_duplicates,
        geometry=skipped_geometry_duplicates
    )


def bulk_create_features_with_fallback(
        features_to_create: List[FeatureStore],
        user_id: int
) -> Tuple[int, int, List[FeatureStore]]:
    """
    Attempt to bulk create features, falling back to individual saves on failure.
    Shared utility used by both single and bulk import jobs.
    
    Args:
        features_to_create: List of FeatureStore objects to create
        user_id: User ID for logging purposes
        
    Returns:
        Tuple of (successful_imports, duplicates_skipped, created_objects)
        created_objects: List of FeatureStore objects that were successfully created
    """
    successful_imports = 0
    duplicates_skipped = 0
    created_objects: List[FeatureStore] = []

    if not features_to_create:
        return 0, 0, []

    try:
        bulk_batch_size = get_required_setting('BULK_CREATE_BATCH_SIZE')
        FeatureStore.objects.bulk_create(features_to_create, batch_size=bulk_batch_size)
        successful_imports = len(features_to_create)
        # After bulk_create, objects have their IDs assigned
        created_objects = features_to_create
    except Exception:
        _logger.warning(f"Bulk import failed for user {user_id}, falling back to individual imports: {traceback.format_exc()}")

        # Fallback to individual creation if bulk fails
        for feature in features_to_create:
            try:
                feature.save()
                successful_imports += 1
                created_objects.append(feature)
            except IntegrityError as e:
                # Hash collision - feature already exists for this user
                if 'unique_user_geojson_hash' in str(e).lower():
                    duplicates_skipped += 1
                    # Skip silently - this is expected behavior
                else:
                    # Unexpected integrity error
                    _logger.error(f"Unexpected integrity error for user {user_id}: {traceback.format_exc()}")
            except Exception:
                _logger.error(f"Error creating individual feature for user {user_id}: {traceback.format_exc()}")

    return successful_imports, duplicates_skipped, created_objects


def finalize_import_item(
    import_item: ImportQueue,
    user_id: int,
    created_features: Optional[List[FeatureStore]] = None
) -> None:
    """
    Mark import item as imported and clean up temporary data.
    Shared utility used by both single and bulk import jobs.
    
    Args:
        import_item: The ImportQueue item to finalize
        user_id: User ID for broadcasting
        created_features: Optional list of FeatureStore objects that were created
    """
    import_item.imported = True

    # Delete logs before clearing the log_id
    if import_item.log_id:
        delete_logs_by_log_id(str(import_item.log_id))

    # Erase unneeded data
    import_item.geofeatures = []
    import_item.log_id = None

    import_item.save()

    # Execute import hooks if any are registered
    if created_features is None:
        created_features = []
    from geo_lib.processing.hooks import execute_import_hooks
    execute_import_hooks(import_item, user_id, created_features)

    # Broadcast WebSocket event
    broadcast_item_imported(user_id, import_item.id)
