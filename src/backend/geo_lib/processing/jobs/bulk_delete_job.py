"""
Bulk delete job processor for asynchronous bulk delete operations.
Handles deletion of multiple import queue items.
"""

import time
import traceback
from typing import Dict, Any, List

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from django.db import transaction

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.jobs.helpers.delete import delete_associated_logs
from geo_lib.processing.messages import BULK_DELETE_JOB_FAILED, ITEM_DELETE_FAILED
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, JobType

_logger = get_tagged_logger('BulkDeleteJob')


class BulkDeleteJob(BaseJob):
    """
    Handles asynchronous bulk deletion of multiple import queue items.
    Processes items sequentially to avoid database contention.
    """

    def get_job_type(self) -> str:
        return "bulk_delete"

    def start_bulk_delete_job(self, item_ids: List[int], user_id: int) -> str | None:
        """
        Start a bulk delete job for multiple import queue items.
        
        Args:
            item_ids: List of ImportQueue item IDs to delete
            user_id: ID of the user who owns the items
            
        Returns:
            Job ID for tracking the bulk deletion
        """
        # Create bulk delete job
        job_id = self.status_tracker.create_job(
            f"Bulk delete of {len(item_ids)} item(s)",
            user_id,
            JobType.BULK_DELETE
        )

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
            _logger.error(f"Bulk delete job {job_id} not found")
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
                _logger.warning(f"Bulk delete job {job_id}: {error_msg}")
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
                except Exception:
                    # Log detailed error internally
                    _logger.error(f"Bulk delete job {job_id}: Error deleting item {item.id}: {traceback.format_exc()}")
                    failed_deletes.append({
                        'item_id': item.id,
                        'filename': item.original_filename,
                        'error': ITEM_DELETE_FAILED
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

            _logger.info(f"Successfully completed bulk delete job {job_id}: {successful_deletes} deleted, {len(failed_deletes)} failed")

        except Exception:
            _logger.error(f"Bulk delete job {job_id} error: {traceback.format_exc()}")
            error_msg = BULK_DELETE_JOB_FAILED
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )
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
            delete_associated_logs(import_queue_item, bulk_job_id)

            # Delete the item
            with transaction.atomic():
                import_queue_item.delete()

            return {'success': True}

        except Exception:
            _logger.error(f"Error deleting item {import_queue_item.id}: {traceback.format_exc()}")
            return {'success': False, 'error': ITEM_DELETE_FAILED}

    def _cancel_active_processing_jobs(self, item_id: int, user_id: int, bulk_job_id: str):
        """
        Cancel any active upload processing jobs for the item being deleted.
        """
        try:
            # Get all user jobs
            user_jobs = self.status_tracker.get_user_jobs(user_id)

            # Find active process jobs for this item (including queued jobs)
            active_process_jobs = [
                job for job in user_jobs
                if (job.import_queue_id == item_id and
                    job.status in (ProcessingStatus.PROCESSING, ProcessingStatus.QUEUED) and
                    job.job_type == JobType.PROCESS)
            ]

            if active_process_jobs:
                _logger.info(f"Found {len(active_process_jobs)} active process jobs for item {item_id}, canceling...")

                # Cancel each active process job
                for process_job in active_process_jobs:
                    if self.status_tracker.cancel_job(process_job.job_id):
                        _logger.info(f"Canceled process job {process_job.job_id} for item {item_id}")

                # Wait briefly for graceful cancellation
                time.sleep(1)

                _logger.info(f"Successfully canceled {len(active_process_jobs)} process jobs for item {item_id}")
            else:
                _logger.info(f"No active process jobs found for item {item_id}")

        except Exception:
            _logger.warning(f"Error canceling active processing jobs for item {item_id}: {traceback.format_exc()}")
            # Don't fail the delete job for this, just log the warning

    def _broadcast_items_deleted(self, user_id: int, item_ids: List[int]):
        """Broadcast WebSocket event when multiple items are deleted."""
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"realtime_{user_id}",
                {
                    'type': 'import_queue_items_deleted',
                    'data': {'ids': item_ids}
                }
            )
