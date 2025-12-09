"""
Import job processor for asynchronous import operations.
Handles importing a single import queue item to the feature store.
"""

import json
from typing import Dict, Any, List

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from api.models import ImportQueue, FeatureStore
from geo_lib.logging.console import get_job_logger
from geo_lib.processing.import_utils import (
    delete_logs_by_log_id,
    broadcast_item_imported,
    process_features_for_import,
    bulk_create_features_with_fallback,
    finalize_import_item,
    build_features_to_skip,
    filter_features_to_process,
)
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.redis_job_storage import update_job_status as update_redis_job_status
from geo_lib.processing.status_tracker import ProcessingStatus

logger = get_job_logger()


class ImportJob(BaseJob):
    """
    Handles asynchronous import of a single import queue item to the feature store.
    """

    def get_job_type(self) -> str:
        return "import"

    def _broadcast_to_process_status_module(self, user_id: int, import_queue_id: int, event_type: str, data: dict):
        """Broadcast WebSocket event to process_status module for specific item."""
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"process_status_{user_id}_{import_queue_id}",
                {
                    'type': event_type,
                    'data': data
                }
            )

    def _handle_job_error(self, job_id: str, error_message: str):
        """
        Handle job errors by updating status and broadcasting via WebSocket.
        Overrides base class to also broadcast to process_status channel.
        """
        # Call parent implementation for general broadcast
        super()._handle_job_error(job_id, error_message)
        
        # Also broadcast to process_status channel if we have item_id
        job = self.status_tracker.get_job(job_id)
        if job and job.import_queue_id:
            self._broadcast_to_process_status_module(
                job.user_id, job.import_queue_id, 'item_failed',
                {
                    'message': f'Import failed: {error_message}',
                    'error': error_message
                }
            )

    def start_import_job(self, item_id: int, user_id: int, import_custom_icons: bool = True, skipped_feature_ids: List[str] = None) -> str:
        """
        Start an import job for a single import queue item.
        
        Args:
            item_id: ImportQueue item ID to import
            user_id: ID of the user who owns the item
            import_custom_icons: Whether to import custom icons (default True)
            skipped_feature_ids: List of feature IDs to skip during import
            
        Returns:
            Job ID string
        """
        if skipped_feature_ids is None:
            skipped_feature_ids = []

        # Create a job
        import_item = ImportQueue.objects.get(id=item_id)
        job_id = self.status_tracker.create_job(f"Import {import_item.original_filename}", user_id)
        
        # Set the import_queue_id so error handling can broadcast to the right channel
        self.status_tracker.set_job_result(job_id, {}, item_id)

        # Start the job
        self.start_job(
            job_id,
            item_id=item_id,
            user_id=user_id,
            import_custom_icons=import_custom_icons,
            skipped_feature_ids=skipped_feature_ids
        )

        return job_id

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the import job processing logic.
        """
        item_id = kwargs['item_id']
        user_id = kwargs['user_id']
        import_custom_icons = kwargs.get('import_custom_icons', True)
        skipped_feature_ids = set(kwargs.get('skipped_feature_ids', []))

        try:
            # Get the import queue item
            import_item = ImportQueue.objects.get(id=item_id, user_id=user_id)
        except ImportQueue.DoesNotExist:
            error_msg = f"Import queue item {item_id} not found"
            logger.error(error_msg)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg,
                error_message=error_msg
            )
            # Update Redis with failure status
            job = self.status_tracker.get_job(job_id)
            if job:
                update_redis_job_status(
                    job_id=job_id,
                    status=ProcessingStatus.FAILED,
                    message=job.message,
                    progress=job.progress,
                    error_message=job.error_message,
                    started_at=job.started_at,
                    completed_at=job.completed_at
                )
            return

        # Update status
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            "Starting feature import...", 10.0
        )

        # Build sets of features to skip (geometry duplicates + manual skips)
        geometry_duplicate_hashes, manually_skipped_non_duplicates, all_features_to_skip = build_features_to_skip(
            import_item, skipped_feature_ids
        )

        # Filter out features to skip before processing
        # Note: Hash duplicates are always blocked by process_features_for_import, no need to filter here
        features_to_process, skipped_count = filter_features_to_process(import_item, all_features_to_skip)

        # Update progress
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            f"Processing {len(features_to_process)} features...", 30.0
        )

        # Process features using shared utility
        # Pass geometry_duplicate_hashes to ensure they're skipped even if processing happens
        features_to_create, skipped_duplicates = process_features_for_import(
            import_item, user_id, import_custom_icons, features_to_process, geometry_duplicate_hashes
        )

        # Update progress
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            f"Importing {len(features_to_create)} features to database...", 70.0
        )

        # Import to database using shared utility
        successful_imports, duplicates_skipped = bulk_create_features_with_fallback(
            features_to_create, user_id
        )

        # Log final summary
        total_processed = len(import_item.geofeatures)
        total_imported = successful_imports
        total_skipped_duplicates = total_processed - skipped_count - len(features_to_create)  # Features skipped due to duplicates or errors

        # Build success message
        msg_parts = [f'Successfully imported {total_imported} features']
        if duplicates_skipped > 0:
            msg_parts.append(f'{duplicates_skipped} duplicates skipped')
        if skipped_count > 0:
            msg_parts.append(f'{skipped_count} skipped by user')
        if total_skipped_duplicates > 0:
            msg_parts.append(f'{total_skipped_duplicates} already existed')
        success_msg = ' (' + ', '.join(msg_parts[1:]) + ')' if len(msg_parts) > 1 else ''
        success_msg = msg_parts[0] + success_msg

        # Only mark as imported and proceed with cleanup if at least one feature was successfully created
        if successful_imports > 0:
            # Finalize import using shared utility
            finalize_import_item(import_item, user_id)

            # Mark job as completed
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED,
                success_msg, 100.0
            )
            # Update Redis with completion status
            job = self.status_tracker.get_job(job_id)
            if job:
                update_redis_job_status(
                    job_id=job_id,
                    status=ProcessingStatus.COMPLETED,
                    message=job.message,
                    progress=job.progress,
                    started_at=job.started_at,
                    completed_at=job.completed_at
                )

            # Broadcast completion event to WebSocket
            # Convert Pydantic model to dict for JSON serialization
            duplicates_skipped_dict = skipped_duplicates.model_dump(mode='json') if skipped_duplicates else {'hash': [], 'coord': []}
            self._broadcast_to_process_status_module(
                user_id, item_id, 'item_completed',
                {
                    'message': success_msg,
                    'imported_count': total_imported,
                    'skipped_count': skipped_count,
                    'duplicates_skipped': duplicates_skipped_dict
                }
            )
        else:
            # No features were successfully imported
            # Determine reason for failure
            if len(features_to_create) == 0:
                if total_processed == 0:
                    reason = "No features found in the file"
                else:
                    reason = f"All {total_processed} features were skipped (duplicates, missing geometry, or unsupported types)"
            else:
                reason = f"Failed to create {len(features_to_create)} features in the database"

            error_msg = f'No features were imported. {reason}.'
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg,
                error_message=error_msg
            )
            # Update Redis with failure status
            job = self.status_tracker.get_job(job_id)
            if job:
                update_redis_job_status(
                    job_id=job_id,
                    status=ProcessingStatus.FAILED,
                    message=job.message,
                    progress=job.progress,
                    error_message=job.error_message,
                    started_at=job.started_at,
                    completed_at=job.completed_at
                )

            # Broadcast failure event to WebSocket
            self._broadcast_to_process_status_module(
                user_id, item_id, 'item_failed',
                {
                    'message': error_msg,
                    'reason': reason
                }
            )
