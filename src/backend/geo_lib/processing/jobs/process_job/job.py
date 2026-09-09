"""
Process job processor for asynchronous file processing.
Handles converting uploaded files to geojson representation.
"""

import time
from typing import Any, Dict, Optional

from redis.exceptions import LockError

from api.models import ImportQueue
from geo_lib.importing.jobs.process_task import run_process_job
from geo_lib.importing.session import ProcessOptions, UploadSession
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.job_ceiling import calculate_job_ceiling_seconds
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus
from geo_lib.processing.jobs.process_job.broadcasting import broadcast_item_added
from geo_lib.processing.jobs.process_job.dispatch import (
    ImportLockContention,
    IMPORT_LOCK_TTL_BUFFER_SECONDS,
    dispatch_import_job,
)
from geo_lib.utils.redis_locks import try_acquire_lock

_logger = get_tagged_logger('ProcessJob')


class ProcessJob(BaseJob):
    """
    Handles asynchronous file processing (converting to geojson).

    Dispatches to a Celery task (queue `imports`). `process_locked` is the task
    entry point and serializes one file at a time per user via a Redis lock.
    """

    def get_job_type(self) -> str:
        return "process"

    def enqueue_job(self, job_id: str, file_data: bytes, filename: str, user_id: int, replacement_feature_id: Optional[int] = None):
        session = UploadSession.open(
            user_id,
            filename,
            file_data,
            replacement_feature_id=replacement_feature_id,
            options=ProcessOptions(minimal_processing=replacement_feature_id is not None),
        )
        import_queue_id = session.queue_id
        self.status_tracker.set_job_import_queue_id(job_id, import_queue_id)
        broadcast_item_added(user_id, import_queue_id)

        job_data = {
            'job_id': job_id,
            'import_queue_id': import_queue_id,
            'filename': filename,
            'user_id': user_id,
            'timestamp': time.time(),
            'replacement_feature_id': replacement_feature_id,
            'job_ceiling_seconds': calculate_job_ceiling_seconds(len(file_data)),
        }

        self.status_tracker.update_job_status(
            job_id,
            ProcessingStatus.QUEUED,
            "Waiting in queue for processing"
        )
        self._broadcast_job_status_updated(
            user_id,
            job_id,
            'queued',
            0.0,
            "Waiting in queue for processing",
            import_queue_id=import_queue_id
        )

        dispatch_import_job(job_id, job_data)

    def process_locked(self, job_id: str, job_data: Dict[str, Any]) -> None:
        user_id = job_data['user_id']
        lock_ttl_seconds = job_data['job_ceiling_seconds'] + IMPORT_LOCK_TTL_BUFFER_SECONDS
        lock = try_acquire_lock(f"import_processing_lock:user:{user_id}", timeout_seconds=lock_ttl_seconds)
        if lock is None:
            raise ImportLockContention(f"Another import job is already processing for user {user_id}")

        try:
            job = self.status_tracker.get_job(job_id)
            if not job or job.status == ProcessingStatus.CANCELED:
                _logger.info(f"Job {job_id} was canceled before processing started")
                queue_id = job_data.get('import_queue_id')
                if queue_id:
                    ImportQueue.objects.filter(id=queue_id).exclude(
                        queue_status__in=ImportQueue.TERMINAL_STATUSES
                    ).update(queue_status=ImportQueue.STATUS_CANCELED)
                return

            self.status_tracker.update_job_status(job_id, ProcessingStatus.PROCESSING, "Processing...", 0.0)
            self._execute_job(job_id, job_data)
        finally:
            try:
                lock.release()
            except LockError:
                pass

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        run_process_job(self.status_tracker, job_id, kwargs)
