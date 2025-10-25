"""
Delete job processor for asynchronous item deletion.
Handles cleanup of items that are still processing and associated resources.
"""

import logging
import time
import traceback
from typing import Dict, Any

from django.contrib.auth.models import User
from django.db import transaction

from data.models import ImportQueue, DatabaseLogging
from geo_lib.processing.status_tracker import ProcessingStatusTracker, ProcessingStatus, JobType
from geo_lib.processing.jobs.base_job import BaseJob

logger = logging.getLogger(__name__)


class DeleteJob(BaseJob):
    """
    Handles asynchronous deletion of import queue items.
    Manages cancellation of active processing jobs and cleanup of associated resources.
    """

    def get_job_type(self) -> str:
        return "delete"

    def start_delete_job(self, item_id: int, user_id: int, filename: str) -> str:
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
            logger.error(f"Delete job {job_id} not found")
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
                logger.warning(f"Delete job {job_id}: {error_msg}")
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
            self._broadcast_job_status_updated(user_id, job_id, "processing", 20.0, "Checking for active processing jobs...")

            # Cancel any active processing job for this item
            self._cancel_active_processing_jobs(item_id, user_id, job_id)

            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Cleaning up associated resources...", 50.0
            )
            
            # Broadcast status update
            self._broadcast_job_status_updated(user_id, job_id, "processing", 50.0, "Cleaning up associated resources...")

            # Delete associated logs
            self._delete_associated_logs(import_queue_item, job_id)

            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Deleting item from database...", 80.0
            )
            
            # Broadcast status update
            self._broadcast_job_status_updated(user_id, job_id, "processing", 80.0, "Deleting item from database...")

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
            
            logger.info(f"Successfully completed delete job {job_id} for item {item_id}")

        except Exception as e:
            error_msg = f"Delete job failed: {str(e)}"
            logger.error(f"Delete job {job_id} error: {error_msg}")
            logger.error(f"Delete job error traceback: {traceback.format_exc()}")
            
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )
            
            # Broadcast failure
            self._broadcast_job_failed(job_id, error_msg, item_id=item_id)

    def _cancel_active_processing_jobs(self, item_id: int, user_id: int, delete_job_id: str):
        """
        Cancel any active import processing jobs for the item being deleted.
        """
        try:
            # Get all user jobs
            user_jobs = self.status_tracker.get_user_jobs(user_id)
            
            # Find active import jobs for this item
            from geo_lib.processing.status_tracker import JobType
            active_import_jobs = [
                job for job in user_jobs 
                if (job.import_queue_id == item_id and 
                    job.status == ProcessingStatus.PROCESSING and
                    job.job_type == JobType.IMPORT)
            ]
            
            if active_import_jobs:
                logger.info(f"Found {len(active_import_jobs)} active import jobs for item {item_id}, cancelling...")
                
                # Update delete job status
                self.status_tracker.update_job_status(
                    delete_job_id, ProcessingStatus.PROCESSING,
                    f"Cancelling {len(active_import_jobs)} active import job(s)...", 30.0
                )
                
                # Cancel each active import job
                for import_job in active_import_jobs:
                    if self.status_tracker.cancel_job(import_job.job_id):
                        logger.info(f"Cancelled import job {import_job.job_id} for item {item_id}")
                
                # Wait briefly for graceful cancellation
                time.sleep(1)
                
                logger.info(f"Successfully cancelled {len(active_import_jobs)} import jobs for item {item_id}")
            else:
                logger.info(f"No active import jobs found for item {item_id}")
                
        except Exception as e:
            logger.warning(f"Error cancelling active processing jobs for item {item_id}: {str(e)}")
            # Don't fail the delete job for this, just log the warning

    def _delete_associated_logs(self, import_queue_item: ImportQueue, delete_job_id: str):
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

