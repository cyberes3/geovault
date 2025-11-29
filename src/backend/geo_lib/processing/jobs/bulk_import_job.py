"""
Bulk import job processor for asynchronous bulk import operations.
Handles importing multiple import queue items to the feature store.
"""

import json
import traceback
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, Any, List, Tuple, Optional

from django.conf import settings
from django.contrib.gis.geos import GEOSGeometry
from website.settings_utils import get_required_setting
from django.db import transaction

from api.models import ImportQueue, FeatureStore, DatabaseLogging
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.status_tracker import ProcessingStatus, JobType
from geo_lib.logging.console import get_job_logger
from geo_lib.processing.import_utils import (
    delete_logs_by_log_id, 
    broadcast_item_imported,
    process_single_feature_for_import,
    apply_bulk_operations
)

logger = get_job_logger()


class BulkImportJob(BaseJob):
    """
    Handles asynchronous bulk import of multiple import queue items.
    Processes items sequentially to avoid database contention.
    """

    def get_job_type(self) -> str:
        return "bulk_import"

    def start_bulk_import_job(self, item_ids: List[int], user_id: int, import_custom_icons: bool = True) -> str:
        """
        Start a bulk import job for multiple import queue items.
        
        Args:
            item_ids: List of ImportQueue item IDs to import
            user_id: ID of the user who owns the items
            import_custom_icons: Whether to import custom icons (default True)
            
        Returns:
            Job ID for tracking the bulk import
        """
        # Create bulk import job
        filename = f"Bulk import of {len(item_ids)} item(s)"
        job_id = self.status_tracker.create_job(filename, user_id, JobType.BULK_IMPORT)

        # Store item IDs and settings in result data
        self.status_tracker.set_job_result(job_id, {
            'item_ids': item_ids,
            'import_custom_icons': import_custom_icons
        })

        # Start the job
        if self.start_job(job_id, item_ids=item_ids, user_id=user_id, import_custom_icons=import_custom_icons):
            return job_id
        else:
            return None

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the bulk import job processing logic.
        """
        item_ids = kwargs['item_ids']
        user_id = kwargs['user_id']
        import_custom_icons = kwargs.get('import_custom_icons', True)

        # Get the job for user info
        job = self.status_tracker.get_job(job_id)
        if not job:
            logger.error(f"Bulk import job {job_id} not found")
            return

        try:
            # Update status to processing
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                f"Starting bulk import of {len(item_ids)} item(s)...", 0.0
            )

            # Broadcast WebSocket event for bulk import start
            self._broadcast_job_started(user_id, job_id, item_ids=item_ids)

            # Get all items that belong to the user
            items = ImportQueue.objects.filter(id__in=item_ids, user_id=user_id)
            found_ids = list(items.values_list('id', flat=True))

            # Check if any requested IDs were not found or don't belong to the user
            missing_ids = set(item_ids) - set(found_ids)
            if missing_ids:
                error_msg = f"Items not found or not authorized: {list(missing_ids)}"
                logger.warning(f"Bulk import job {job_id}: {error_msg}")
                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.FAILED,
                    error_msg, error_message=error_msg
                )
                self._broadcast_job_failed(job_id, error_msg)
                return

            # Process each item sequentially
            total_items = len(found_ids)
            successful_imports = 0
            failed_imports = []
            skipped_items = []

            for index, item in enumerate(items):
                item_progress = (index / total_items) * 100.0
                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.PROCESSING,
                    f"Importing item {index + 1}/{total_items}: {item.original_filename}...", item_progress
                )
                self._broadcast_job_status_updated(
                    user_id, job_id, "processing", item_progress,
                    f"Importing item {index + 1}/{total_items}: {item.original_filename}...",
                    current_item_id=item.id, current_item_filename=item.original_filename
                )

                try:
                    # Import this item
                    result = self._import_single_item(item, user_id, import_custom_icons)
                    if result['success']:
                        successful_imports += 1
                    else:
                        failed_imports.append({
                            'item_id': item.id,
                            'filename': item.original_filename,
                            'error': result['error']
                        })
                except Exception as e:
                    error_msg = str(e)
                    logger.error(f"Bulk import job {job_id}: Error importing item {item.id}: {error_msg}")
                    logger.error(f"Bulk import error traceback: {traceback.format_exc()}")
                    failed_imports.append({
                        'item_id': item.id,
                        'filename': item.original_filename,
                        'error': error_msg
                    })

            # Mark as completed
            if failed_imports:
                completion_msg = f"Completed: {successful_imports} imported, {len(failed_imports)} failed"
            else:
                completion_msg = f"Successfully imported {successful_imports} item(s)"

            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED,
                completion_msg, 100.0
            )

            # Broadcast completion
            self._broadcast_job_completed(
                user_id, job_id,
                item_ids=found_ids,
                successful_count=successful_imports,
                failed_count=len(failed_imports),
                failed_items=failed_imports
            )

            logger.info(f"Successfully completed bulk import job {job_id}: {successful_imports} imported, {len(failed_imports)} failed")

        except Exception as e:
            error_msg = f"Bulk import job failed: {str(e)}"
            logger.error(f"Bulk import job {job_id} error: {error_msg}")
            logger.error(f"Bulk import job error traceback: {traceback.format_exc()}")

            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )

            # Broadcast failure
            self._broadcast_job_failed(job_id, error_msg)

    def _import_single_item(self, import_item: ImportQueue, user_id: int, import_custom_icons: bool) -> Dict[str, Any]:
        """
        Import a single import queue item to the feature store.
        Reuses logic from import_to_featurestore function.
        
        Returns:
            Dict with 'success' (bool) and 'error' (str if failed)
        """
        try:
            # Prevent importing items that have already been imported
            if import_item.imported:
                return {'success': False, 'error': 'Item already imported'}

            # Check for file-level duplicates before importing
            if import_item.geojson_hash:
                earlier_duplicates = ImportQueue.objects.filter(
                    user_id=user_id,
                    geojson_hash=import_item.geojson_hash,
                    imported=False,
                    timestamp__lt=import_item.timestamp
                ).order_by('timestamp').first()
                
                if earlier_duplicates:
                    return {'success': False, 'error': f'Duplicate of "{earlier_duplicates.original_filename}"'}

            # Prepare features for bulk import
            features_to_create = []
            existing_hashes = set()
            current_batch_hashes = set()

            # Get existing feature hashes for this user to avoid duplicates
            existing_features = FeatureStore.objects.filter(user_id=user_id).values_list('file_hash', flat=True)
            existing_hashes.update(existing_features)

            # Thread-safe duplicate checking
            duplicate_check_lock = threading.Lock()
            
            def process_feature_with_index(args: Tuple[int, Dict[str, Any]]) -> Optional[FeatureStore]:
                """Wrapper to unpack index and feature for executor.map()"""
                feature_index, feature = args
                return process_single_feature_for_import(
                    feature, feature_index, import_item, user_id, import_custom_icons,
                    existing_hashes, current_batch_hashes, duplicate_check_lock
                )

            # Get number of threads from settings
            num_threads = get_required_setting('IMPORT_PROCESSING_THREADS')
            
            # Apply bulk operations to features before processing
            bulk_ops = import_item.bulk_operations or {}
            if bulk_ops:
                import_item.geofeatures = apply_bulk_operations(import_item.geofeatures, bulk_ops)

            # Process features in parallel using ThreadPoolExecutor
            if len(import_item.geofeatures) > 0:
                with ThreadPoolExecutor(max_workers=num_threads) as executor:
                    results = executor.map(
                        process_feature_with_index,
                        enumerate(import_item.geofeatures)
                    )
                    
                    for feature_store in results:
                        if feature_store is not None:
                            features_to_create.append(feature_store)

            # Track successful feature creation
            successful_imports = 0

            # Bulk create all features at once for better performance
            if features_to_create:
                try:
                    bulk_batch_size = get_required_setting('BULK_CREATE_BATCH_SIZE')
                    FeatureStore.objects.bulk_create(features_to_create, batch_size=bulk_batch_size)
                    successful_imports = len(features_to_create)
                except Exception as e:
                    logger.warning(f"Bulk import failed for user {user_id}, falling back to individual imports: {str(e)}")
                    # Fallback to individual creation if bulk fails
                    for feature in features_to_create:
                        try:
                            feature.save()
                            successful_imports += 1
                        except Exception as individual_error:
                            if "duplicate key" not in str(individual_error).lower():
                                logger.error(f"Error creating individual feature for user {user_id}: {individual_error}")

            # Only mark as imported if at least one feature was successfully created
            if successful_imports > 0:
                # Mark as imported only after successful feature creation
                import_item.imported = True

                # Delete logs before clearing the log_id
                if import_item.log_id:
                    delete_logs_by_log_id(str(import_item.log_id))

                # Erase some unneeded data
                import_item.geofeatures = []
                import_item.log_id = None

                import_item.save()
                
                # Broadcast WebSocket event for item import
                broadcast_item_imported(user_id, import_item.id)

                return {'success': True}
            else:
                return {'success': False, 'error': 'No features were imported'}

        except Exception as e:
            logger.error(f"Error importing item {import_item.id}: {str(e)}")
            return {'success': False, 'error': str(e)}

