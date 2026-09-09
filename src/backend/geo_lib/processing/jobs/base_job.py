"""
Base job class for all asynchronous operations.
"""

import traceback
from abc import ABC, abstractmethod
from typing import Dict, Any

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.messages import JOB_FAILED_GENERIC
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatusTracker, ProcessingStatus

_logger = get_tagged_logger('BaseJob')


class BaseJob(ABC):
    """
    Base class for all asynchronous job processors.
    Provides common functionality for job management, status tracking, and WebSocket broadcasting.
    """

    def __init__(self, status_tracker: ProcessingStatusTracker):
        self.status_tracker = status_tracker

    @abstractmethod
    def get_job_type(self) -> str:
        """Return the type of job (e.g., 'import', 'delete')."""
        raise NotImplementedError()

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
        return self.status_tracker.cancel_job(job_id)

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
        self._broadcast_websocket_event(user_id, 'completed', {'job_id': job_id, **data})

    def _broadcast_job_failed(self, job_id: str, error_message: str, **data):
        """Broadcast WebSocket event when a job fails."""
        job = self.status_tracker.get_job(job_id)
        if not job:
            return

        self._broadcast_websocket_event(job.user_id, 'failed', {
            'job_id': job_id,
            'error_message': error_message,
            **data
        })
