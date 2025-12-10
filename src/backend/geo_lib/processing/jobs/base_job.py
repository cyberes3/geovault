"""
Base job class for all asynchronous operations.
"""

import threading
import traceback
from abc import ABC, abstractmethod
from typing import Dict, Any

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.redis_job_storage import (
    store_job_started,
    update_job_status as update_redis_job_status
)
from geo_lib.processing.messages import JOB_FAILED_GENERIC
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatusTracker, ProcessingStatus

_logger = get_tagged_logger('job')


class BaseJob(ABC):
    """
    Base class for all asynchronous job processors.
    Provides common functionality for job management, status tracking, and WebSocket broadcasting.
    """

    def __init__(self, status_tracker: ProcessingStatusTracker):
        self.status_tracker = status_tracker
        self._active_threads: Dict[str, threading.Thread] = {}

    @abstractmethod
    def get_job_type(self) -> str:
        """Return the type of job (e.g., 'import', 'delete')."""
        raise NotImplementedError()

    def start_job(self, job_id: str, **kwargs) -> bool:
        """
        Start a job in a background thread.
        
        Args:
            job_id: Unique job identifier
            **kwargs: Job-specific parameters
            
        Returns:
            True if job started successfully, False otherwise
        """
        # Check if job already exists and is not canceled
        job = self.status_tracker.get_job(job_id)
        if not job or job.status == ProcessingStatus.CANCELED:
            _logger.warning(f"Cannot start {self.get_job_type()} job {job_id}: job not found or canceled")
            return False

        # Check if already processing
        if job_id in self._active_threads:
            _logger.warning(f"Job {job_id} is already being processed")
            return False

        # Start processing in background thread
        thread = threading.Thread(
            target=self._job_worker,
            args=(job_id, kwargs),
            daemon=True
        )
        thread.start()

        self._active_threads[job_id] = thread
        return True

    def _job_worker(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Worker function that runs in a background thread to process the job.
        Checks for cancellation before and during processing.
        """
        try:
            # Check if job was canceled before starting processing
            job = self.status_tracker.get_job(job_id)
            if not job or job.status == ProcessingStatus.CANCELED:
                _logger.info(f"Job {job_id} was canceled before processing started")
                return

            # Store job in Redis when it starts processing
            store_job_started(
                job_id=job_id,
                user_id=job.user_id,
                job_type=self.get_job_type(),
                filename=job.filename,
                created_at=job.created_at,
                import_queue_id=getattr(job, 'import_queue_id', None)
            )

            # Execute the job-specific processing
            self._execute_job(job_id, kwargs)

            # Check if job was canceled after processing
            job = self.status_tracker.get_job(job_id)
            if job.status == ProcessingStatus.CANCELED:
                _logger.info(f"Job {job_id} was canceled during processing")

        except Exception as e:
            # Don't log error if job was canceled
            job = self.status_tracker.get_job(job_id)
            if job.status == ProcessingStatus.CANCELED:
                _logger.info(f"Job {job_id} was canceled, stopping error handling")
            else:
                # Log detailed error internally
                _logger.error(f"Error in {self.get_job_type()} job {job_id}: {traceback.format_exc()}")
                # Use generic error message for user
                self._handle_job_error(job_id, JOB_FAILED_GENERIC)

        finally:
            # Clean up thread reference
            if job_id in self._active_threads:
                del self._active_threads[job_id]

    @abstractmethod
    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the job-specific processing logic.
        Must be implemented by subclasses.
        """
        raise NotImplementedError()

    def _handle_job_error(self, job_id: str, error_message: str):
        """
        Handle job errors by updating status and broadcasting via WebSocket.
        """
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.FAILED,
            f"Job failed: {error_message}",
            error_message=error_message
        )

        # Broadcast WebSocket event for job failure
        self._broadcast_job_failed(job_id, error_message)

    def cancel_job(self, job_id: str) -> bool:
        """Cancel a job if it's not already completed."""
        result = self.status_tracker.cancel_job(job_id)
        if result:
            # Update Redis with cancellation status
            job = self.status_tracker.get_job(job_id)
            update_redis_job_status(
                job_id=job_id,
                status=ProcessingStatus.CANCELED,
                message=job.message,
                progress=job.progress,
                started_at=job.started_at,
                completed_at=job.completed_at
            )
        return result

    def get_active_jobs(self) -> Dict[str, Any]:
        """Get statistics about current active jobs."""
        return {
            'active_threads': len(self._active_threads),
            'thread_job_ids': list(self._active_threads.keys()),
            'status_tracker_stats': self.status_tracker.get_stats()
        }

    def _broadcast_websocket_event(self, user_id: int, event_type: str, data: Dict[str, Any]):
        """Unified WebSocket broadcast helper."""
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"realtime_{user_id}",
                {
                    'type': f'{self.get_job_type()}_job_{event_type}',
                    'data': data
                }
            )

    def _broadcast_job_started(self, user_id: int, job_id: str, **data):
        """Broadcast WebSocket event when a job starts."""
        self._broadcast_websocket_event(user_id, 'started', {'job_id': job_id, **data})

    def _broadcast_job_status_updated(self, user_id: int, job_id: str, status: str, progress: float, message: str, **kwargs):
        """Broadcast WebSocket event when job status is updated."""
        # Update Redis if status is PROCESSING (job actually started)
        job = self.status_tracker.get_job(job_id)
        if status == 'processing' and job.status == ProcessingStatus.PROCESSING:
            update_redis_job_status(
                job_id=job_id,
                status=ProcessingStatus.PROCESSING,
                message=message,
                progress=progress,
                started_at=job.started_at,
                **kwargs
            )

        data = {
            'job_id': job_id,
            'status': status,
            'progress': progress,
            'message': message
        }
        # Include any additional data (like import_queue_id)
        data.update(kwargs)
        self._broadcast_websocket_event(user_id, 'status_updated', data)

    def _broadcast_job_completed(self, user_id: int, job_id: str, **data):
        """Broadcast WebSocket event when a job completes."""
        # Update Redis with completion status
        job = self.status_tracker.get_job(job_id)
        update_redis_job_status(
            job_id=job_id,
            status=ProcessingStatus.COMPLETED,
            message=job.message,
            progress=job.progress,
            started_at=job.started_at,
            completed_at=job.completed_at,
            **data
        )

        self._broadcast_websocket_event(user_id, 'completed', {'job_id': job_id, **data})

    def _broadcast_job_failed(self, job_id: str, error_message: str, **data):
        """Broadcast WebSocket event when a job fails."""
        # Get user_id from job
        job = self.status_tracker.get_job(job_id)
        if not job:
            return

        # Update Redis with failure status
        update_redis_job_status(
            job_id=job_id,
            status=ProcessingStatus.FAILED,
            message=job.message,
            progress=job.progress,
            error_message=error_message,
            started_at=job.started_at,
            completed_at=job.completed_at,
            **data
        )

        self._broadcast_websocket_event(job.user_id, 'failed', {
            'job_id': job_id,
            'error_message': error_message,
            **data
        })
