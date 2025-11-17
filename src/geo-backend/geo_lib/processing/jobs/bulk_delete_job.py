"""
Bulk delete job processor for asynchronous bulk delete operations.
Handles deletion of multiple import queue items.
"""

import time
import traceback
from typing import Dict, Any, List

from django.db import transaction

from api.models import ImportQueue, DatabaseLogging
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.status_tracker import ProcessingStatus, JobType
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


class BulkDeleteJob(BaseJob):
    """
    Handles asynchronous bulk deletion of multiple import queue items.
    Processes items sequentially to avoid database contention.
    """

    def get_job_type(self) -> str:
        return "bulk_delete"

    def start_bulk_delete_job(self, item_ids: List[int], user_id: int) -> str:
        """
        Start a bulk delete job for multiple import queue items.
        
        Args:
            item_ids: List of ImportQueue item IDs to delete
            user_id: ID of the user who owns the items
            
        Returns:
            Job ID for tracking the bulk deletion
        """
        # Create bulk delete job
        filename = f"Bulk delete of {len(item_ids)} item(s)"
        job_id = self.status_tracker.create_job(filename, user_id, JobType.BULK_DELETE)

        # Store item IDs in result data
        self.status_tracker.set_job_result(job_id, {'item_ids': item_ids})

        # Start the job
        if self.start_job(job_id, item_ids=item_ids, user_id=user_id):
            return job_id
        else:
            return None

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the bulk delete job processing logic.
        """
        item_ids = kwargs['item_ids']
        user_id = kwargs['user_id']

        # Get the job for user info
        job = self.status_tracker.get_job(job_id)
        if not job:
            logger.error(f"Bulk delete job {job_id} not found")
            return

        try:
            # Update status to processing
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                f"Starting bulk delete of {len(item_ids)} item(s)...", 0.0
            )

            # Broadcast WebSocket event for bulk delete start
            self._broadcast_job_started(user_id, job_id, item_ids=item_ids)

            # Get all items that belong to the user
            items = ImportQueue.objects.filter(id__in=item_ids, user_id=user_id)
            found_ids = list(items.values_list('id', flat=True))

            # Check if any requested IDs were not found or don't belong to the user
            missing_ids = set(item_ids) - set(found_ids)
            if missing_ids:
                error_msg = f"Items not found or not authorized: {list(missing_ids)}"
                logger.warning(f"Bulk delete job {job_id}: {error_msg}")
                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.FAILED,
                    error_msg, error_message=error_msg
                )
                self._broadcast_job_failed(job_id, error_msg)
                return

            # Process each item sequentially
            total_items = len(found_ids)
            successful_deletes = 0
            failed_deletes = []

            for index, item in enumerate(items):
                item_progress = (index / total_items) * 100.0
                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.PROCESSING,
                    f"Deleting item {index + 1}/{total_items}: {item.original_filename}...", item_progress
                )
                self._broadcast_job_status_updated(
                    user_id, job_id, "processing", item_progress,
                    f"Deleting item {index + 1}/{total_items}: {item.original_filename}...",
                    current_item_id=item.id, current_item_filename=item.original_filename
                )

                try:
                    # Delete this item
                    result = self._delete_single_item(item, user_id, job_id)
                    if result['success']:
                        successful_deletes += 1
                    else:
                        failed_deletes.append({
                            'item_id': item.id,
                            'filename': item.original_filename,
                            'error': result['error']
                        })
                except Exception as e:
                    error_msg = str(e)
                    logger.error(f"Bulk delete job {job_id}: Error deleting item {item.id}: {error_msg}")
                    logger.error(f"Bulk delete error traceback: {traceback.format_exc()}")
                    failed_deletes.append({
                        'item_id': item.id,
                        'filename': item.original_filename,
                        'error': error_msg
                    })

            # Mark as completed
            if failed_deletes:
                completion_msg = f"Completed: {successful_deletes} deleted, {len(failed_deletes)} failed"
            else:
                completion_msg = f"Successfully deleted {successful_deletes} item(s)"

            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED,
                completion_msg, 100.0
            )

            # Broadcast completion
            self._broadcast_job_completed(
                user_id, job_id,
                item_ids=found_ids,
                successful_count=successful_deletes,
                failed_count=len(failed_deletes),
                failed_items=failed_deletes
            )

            # Broadcast items deleted event
            if successful_deletes > 0:
                deleted_ids = [item.id for item in items if item.id not in [f['item_id'] for f in failed_deletes]]
                self._broadcast_items_deleted(user_id, deleted_ids)

            logger.info(f"Successfully completed bulk delete job {job_id}: {successful_deletes} deleted, {len(failed_deletes)} failed")

        except Exception as e:
            error_msg = f"Bulk delete job failed: {str(e)}"
            logger.error(f"Bulk delete job {job_id} error: {error_msg}")
            logger.error(f"Bulk delete job error traceback: {traceback.format_exc()}")

            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )

            # Broadcast failure
            self._broadcast_job_failed(job_id, error_msg)

    def _delete_single_item(self, import_queue_item: ImportQueue, user_id: int, bulk_job_id: str) -> Dict[str, Any]:
        """
        Delete a single import queue item.
        Reuses logic from DeleteJob.
        
        Returns:
            Dict with 'success' (bool) and 'error' (str if failed)
        """
        try:
            # Cancel any active processing jobs for this item
            self._cancel_active_processing_jobs(import_queue_item.id, user_id, bulk_job_id)

            # Delete associated logs
            self._delete_associated_logs(import_queue_item, bulk_job_id)

            # Delete the item
            with transaction.atomic():
                import_queue_item.delete()

            return {'success': True}

        except Exception as e:
            logger.error(f"Error deleting item {import_queue_item.id}: {str(e)}")
            logger.error(f"Delete error traceback: {traceback.format_exc()}")
            return {'success': False, 'error': str(e)}

    def _cancel_active_processing_jobs(self, item_id: int, user_id: int, bulk_job_id: str):
        """
        Cancel any active upload processing jobs for the item being deleted.
        """
        try:
            # Get all user jobs
            user_jobs = self.status_tracker.get_user_jobs(user_id)

            # Find active upload jobs for this item
            from geo_lib.processing.status_tracker import JobType
            active_upload_jobs = [
                job for job in user_jobs
                if (job.import_queue_id == item_id and
                    job.status == ProcessingStatus.PROCESSING and
                    job.job_type == JobType.UPLOAD)
            ]

            if active_upload_jobs:
                logger.info(f"Found {len(active_upload_jobs)} active upload jobs for item {item_id}, cancelling...")

                # Cancel each active upload job
                for upload_job in active_upload_jobs:
                    if self.status_tracker.cancel_job(upload_job.job_id):
                        logger.info(f"Cancelled upload job {upload_job.job_id} for item {item_id}")

                # Wait briefly for graceful cancellation
                time.sleep(1)

                logger.info(f"Successfully cancelled {len(active_upload_jobs)} upload jobs for item {item_id}")
            else:
                logger.info(f"No active upload jobs found for item {item_id}")

        except Exception as e:
            logger.warning(f"Error cancelling active processing jobs for item {item_id}: {str(e)}")
            # Don't fail the delete job for this, just log the warning

    def _delete_associated_logs(self, import_queue_item: ImportQueue, bulk_job_id: str):
        """
        Delete all logs associated with the import queue item.
        """
        try:
            if import_queue_item.log_id:
                deleted_count = DatabaseLogging.objects.filter(log_id=import_queue_item.log_id).delete()[0]
                logger.info(f"Deleted {deleted_count} log entries for item {import_queue_item.id}")
            else:
                logger.info(f"No log_id found for item {import_queue_item.id}")

        except Exception as e:
            logger.warning(f"Error deleting logs for item {import_queue_item.id}: {str(e)}")
            # Don't fail the delete job for this, just log the warning

    def _broadcast_items_deleted(self, user_id: int, item_ids: List[int]):
        """Broadcast WebSocket event when multiple items are deleted."""
        from channels.layers import get_channel_layer
        from asgiref.sync import async_to_sync
        
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"realtime_{user_id}",
                {
                    'type': 'import_queue_items_deleted',
                    'data': {'ids': item_ids}
                }
            )

