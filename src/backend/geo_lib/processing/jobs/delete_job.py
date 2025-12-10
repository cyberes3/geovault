"""
Delete job processor for asynchronous item deletion.
Handles cleanup of items that are still processing and associated resources.
"""

import time
import traceback
from typing import Dict, Any

from django.db import transaction

from api.models import ImportQueue
from geo_lib.logging.console import get_job_logger
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.jobs.helpers.delete import delete_associated_logs
from geo_lib.processing.messages import DELETE_JOB_FAILED
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, JobType

_logger = get_job_logger()


class DeleteJob(BaseJob):
    """
    Handles asynchronous deletion of import queue items.
    Manages cancellation of active processing jobs and cleanup of associated resources.
    """

    def get_job_type(self) -> str:
        return "delete"

    def start_delete_job(self, item_id: int, user_id: int, filename: str) -> str | None:
        """
        Start a delete job for an import queue item.
        
        Args:
            item_id: ID of the ImportQueue item to delete
            user_id: ID of the user who owns the item
            filename: Original filename for logging
            
        Returns:
            Job ID for tracking the deletion
        """
        # Create delete job
        job_id = self.status_tracker.create_job(filename, user_id, JobType.DELETE)

        # Set the import_queue_id for tracking
        self.status_tracker.set_job_result(job_id, {}, item_id)

        # Start the job
        if self.start_job(job_id, item_id=item_id, user_id=user_id, filename=filename):
            return job_id
        else:
            return None

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the delete job processing logic.
        """
        item_id = kwargs['item_id']
        user_id = kwargs['user_id']
        filename = kwargs['filename']

        # Get the job for user info
        job = self.status_tracker.get_job(job_id)
        if not job:
            _logger.error(f"Delete job {job_id} not found")
            return

        try:
            # Update status to processing
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Starting item deletion...", 10.0
            )

            # Broadcast WebSocket event for deletion start
            self._broadcast_job_started(user_id, job_id, item_id=item_id)

            # Check if item exists and user owns it
            try:
                import_queue_item = ImportQueue.objects.get(id=item_id, user_id=user_id)
            except ImportQueue.DoesNotExist:
                error_msg = f"Item {item_id} not found or not authorized"
                _logger.warning(f"Delete job {job_id}: {error_msg}")
                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.FAILED,
                    error_msg, error_message=error_msg
                )
                self._broadcast_job_failed(job_id, error_msg)
                return

            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Checking for active processing jobs...", 20.0
            )

            # Broadcast status update
            self._broadcast_job_status_updated(user_id, job_id, "processing", 20.0, "Checking for active processing jobs...", item_id=item_id)

            # Cancel any active processing job for this item
            self._cancel_active_processing_jobs(item_id, user_id, job_id)

            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Cleaning up associated resources...", 50.0
            )

            # Broadcast status update
            self._broadcast_job_status_updated(user_id, job_id, "processing", 50.0, "Cleaning up associated resources...", item_id=item_id)

            # Delete associated logs
            delete_associated_logs(import_queue_item, job_id)

            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Deleting item from database...", 80.0
            )

            # Broadcast status update
            self._broadcast_job_status_updated(user_id, job_id, "processing", 80.0, "Deleting item from database...", item_id=item_id)

            # Delete the item
            with transaction.atomic():
                import_queue_item.delete()

            # Mark as completed
            completion_msg = f"Successfully deleted '{filename}'"
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED,
                completion_msg, 100.0
            )

            # Broadcast completion
            self._broadcast_job_completed(user_id, job_id, item_id=item_id)

            _logger.info(f"Successfully completed delete job {job_id} for item {item_id}")

        except:
            _logger.error(f"Delete job {job_id} error: {traceback.format_exc}")
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                DELETE_JOB_FAILED, error_message=DELETE_JOB_FAILED
            )
            self._broadcast_job_failed(job_id, DELETE_JOB_FAILED, item_id=item_id)

    def _cancel_active_processing_jobs(self, item_id: int, user_id: int, delete_job_id: str):
        """
        Cancel any active upload processing jobs for the item being deleted.
        """
        try:
            # Get all user jobs
            user_jobs = self.status_tracker.get_user_jobs(user_id)

            # Find active process jobs for this item (including waiting jobs)
            active_process_jobs = [
                job for job in user_jobs
                if (job.import_queue_id == item_id and
                    job.status in (ProcessingStatus.PROCESSING, ProcessingStatus.WAITING) and
                    job.job_type == JobType.PROCESS)
            ]

            if active_process_jobs:
                _logger.info(f"Found {len(active_process_jobs)} active process jobs for item {item_id}, cancelling...")

                # Update delete job status
                self.status_tracker.update_job_status(
                    delete_job_id, ProcessingStatus.PROCESSING,
                    f"Cancelling {len(active_process_jobs)} active process job(s)...", 30.0
                )

                # Cancel each active process job
                for process_job in active_process_jobs:
                    if self.status_tracker.cancel_job(process_job.job_id):
                        _logger.info(f"Cancelled process job {process_job.job_id} for item {item_id}")

                # Wait briefly for graceful cancellation
                time.sleep(1)

                _logger.info(f"Successfully cancelled {len(active_process_jobs)} process jobs for item {item_id}")
            else:
                _logger.info(f"No active process jobs found for item {item_id}")

        except:
            _logger.warning(f"Error cancelling active processing jobs for item {item_id}: {traceback.format_exc()}")
            # Don't fail the delete job for this, just log the warning
