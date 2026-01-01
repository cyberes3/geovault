"""
Bulk import job processor for asynchronous bulk import operations.
Handles importing multiple import queue items to the feature store.
"""

import traceback
from typing import Dict, Any, List

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.duplicate_detection.models import SkippedDuplicates
from geo_lib.processing.import_operations.batch_operations import (
    process_features_for_import,
    bulk_create_features_with_fallback,
    finalize_import_item,
)
from geo_lib.processing.import_operations.results import (
    job_success_result,
    job_error_result,
)
from geo_lib.processing.import_operations.skip_logic import (
    build_features_to_skip,
    filter_features_to_process,
)
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.messages import BULK_IMPORT_JOB_FAILED, ITEM_IMPORT_FAILED
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, JobType

_logger = get_tagged_logger('BulkImportJob')


class BulkImportJob(BaseJob):
    """
    Handles asynchronous bulk import of multiple import queue items.
    Processes items sequentially to avoid database contention.
    """

    def get_job_type(self) -> str:
        return "bulk_import"

    def start_bulk_import_job(self, item_ids: List[int], user_id: int, import_custom_icons: bool = True) -> str | None:
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
            _logger.error(f"Bulk import job {job_id} not found")
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
                _logger.warning(f"Bulk import job {job_id}: {error_msg}")
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
            all_skipped_duplicates = SkippedDuplicates()  # Aggregate skipped duplicates across all items

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
                        # Aggregate skipped duplicates from this item
                        if 'duplicates_skipped' in result and result['duplicates_skipped']:
                            # result['duplicates_skipped'] is a dict from job_success_result
                            # Convert back to SkippedDuplicates model to aggregate
                            item_skipped = SkippedDuplicates.model_validate(result['duplicates_skipped'])
                            all_skipped_duplicates.hash.extend(item_skipped.hash)
                            all_skipped_duplicates.geometry.extend(item_skipped.geometry)
                    else:
                        failed_imports.append({
                            'item_id': item.id,
                            'filename': item.original_filename,
                            'error': result['error']
                        })
                except:
                    # Log detailed error internally
                    _logger.error(f"Bulk import job {job_id}: Error importing item {item.id}: {traceback.format_exc()}")
                    # Use generic error message for user
                    failed_imports.append({
                        'item_id': item.id,
                        'filename': item.original_filename,
                        'error': ITEM_IMPORT_FAILED
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

            # Convert aggregated skipped duplicates to dict for JSON serialization
            duplicates_skipped_dict = all_skipped_duplicates.model_dump(mode='json') if all_skipped_duplicates else {'hash': [], 'geometry': []}

            # Broadcast completion
            self._broadcast_job_completed(
                user_id, job_id,
                item_ids=found_ids,
                successful_count=successful_imports,
                failed_count=len(failed_imports),
                failed_items=failed_imports,
                duplicates_skipped=duplicates_skipped_dict
            )

            _logger.info(f"Successfully completed bulk import job {job_id}: {successful_imports} imported, {len(failed_imports)} failed")
            
            # Log details of failed imports
            if failed_imports:
                for failed_item in failed_imports:
                    _logger.warning(
                        f"Bulk import job {job_id}: Failed to import item {failed_item['item_id']} "
                        f"({failed_item['filename']}): {failed_item['error']}"
                    )

        except:
            _logger.error(f"Bulk import job {job_id} error: {traceback.format_exc()}")
            error_msg = BULK_IMPORT_JOB_FAILED
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )
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
                return job_error_result('Item already imported')

            # Check for file-level duplicates before importing
            if import_item.file_hash:
                earlier_duplicates = ImportQueue.objects.filter(
                    user_id=user_id,
                    file_hash=import_item.file_hash,
                    imported=False,
                    timestamp__lt=import_item.timestamp
                ).order_by('timestamp').first()

                if earlier_duplicates:
                    return job_error_result(f'Duplicate of "{earlier_duplicates.original_filename}"')

            # Process features using shared utility
            # For ready-to-import table imports, skip both hash and geometry duplicates automatically
            # Build sets of features to skip (geometry duplicates + manual skips)
            # Note: Bulk import doesn't receive user_skipped_feature_ids, only uses saved state
            geometry_duplicate_hashes, manually_skipped_non_duplicates, all_features_to_skip = build_features_to_skip(
                import_item, user_skipped_feature_ids=None
            )

            # Track total features in file
            total_features_in_file = len(import_item.geofeatures) if import_item.geofeatures else 0

            # Filter out features to skip before processing
            # Note: Hash duplicates are always blocked by process_features_for_import, no need to filter here
            features_to_process, skipped_before_processing = filter_features_to_process(import_item, all_features_to_skip)

            features_to_create, skipped_duplicates = process_features_for_import(
                import_item, user_id, import_custom_icons, features_to_process, geometry_duplicate_hashes
            )

            # Count skipped duplicates
            hash_duplicates_count = len(skipped_duplicates.hash) if skipped_duplicates else 0
            geometry_duplicates_count = len(skipped_duplicates.geometry) if skipped_duplicates else 0

            # Import to database using shared utility
            successful_imports, duplicates_skipped, created_features = bulk_create_features_with_fallback(
                features_to_create, user_id
            )

            # Only mark as imported if at least one feature was successfully created
            if successful_imports > 0:
                # Finalize import using shared utility (pass created_features for hooks)
                finalize_import_item(import_item, user_id, created_features)

                _logger.info(f"Imported {successful_imports} features for user {user_id}")

                # Convert Pydantic model to dict for JSON serialization
                # job_success_result expects a dict, so convert the Pydantic model
                duplicates_skipped_dict = skipped_duplicates.model_dump(mode='json') if skipped_duplicates else {'hash': [], 'geometry': []}
                return job_success_result(
                    imported=successful_imports,
                    duplicates_skipped=duplicates_skipped_dict
                )
            else:
                # Build detailed error message explaining why no features were imported
                error_parts = []
                
                if total_features_in_file == 0:
                    error_parts.append("No features found in file")
                else:
                    error_parts.append(f"File contains {total_features_in_file} feature(s)")
                    
                    if skipped_before_processing > 0:
                        error_parts.append(f"{skipped_before_processing} skipped before processing (geometry duplicates or manually skipped)")
                    
                    if hash_duplicates_count > 0:
                        error_parts.append(f"{hash_duplicates_count} skipped as hash duplicates")
                    
                    if geometry_duplicates_count > 0:
                        error_parts.append(f"{geometry_duplicates_count} skipped as geometry duplicates")
                    
                    if len(features_to_create) == 0:
                        if len(features_to_process) == 0:
                            error_parts.append("All features were filtered out before processing")
                        else:
                            error_parts.append(f"All {len(features_to_process)} processed feature(s) were skipped as duplicates")
                    else:
                        error_parts.append(f"{len(features_to_create)} feature(s) ready to import but database insert failed")
                
                error_msg = "No features were imported. " + ". ".join(error_parts) + "."
                return job_error_result(error_msg)

        except:
            _logger.error(f"Error importing item {import_item.id}: {traceback.format_exc()}")
            return job_error_result(ITEM_IMPORT_FAILED)
