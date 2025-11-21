"""
In-memory status tracker for asynchronous file processing.
Tracks multiple concurrent file uploads and their processing status.
"""

import threading
import time
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Dict, Optional, Any

from django.conf import settings
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


class ProcessingStatus(Enum):
    """Status of file processing."""
    UPLOADED = "uploaded"  # File uploaded, waiting to start processing
    PROCESSING = "processing"  # Currently being processed
    COMPLETED = "completed"  # Processing completed successfully
    FAILED = "failed"  # Processing failed
    CANCELLED = "cancelled"  # Processing was cancelled


class JobType(Enum):
    """Type of job being processed."""
    UPLOAD = "upload"  # File upload job
    DELETE = "delete"  # Item deletion job
    BULK_IMPORT = "bulk_import"  # Bulk import job
    BULK_DELETE = "bulk_delete"  # Bulk delete job


@dataclass
class ProcessingJob:
    """Represents a file processing job."""
    job_id: str
    filename: str
    user_id: int
    status: ProcessingStatus
    job_type: JobType
    created_at: float
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    progress: float = 0.0  # 0.0 to 100.0
    message: str = ""
    error_message: Optional[str] = None
    result_data: Optional[Dict[str, Any]] = None
    import_queue_id: Optional[int] = None


class ProcessingStatusTracker:
    """
    Thread-safe in-memory tracker for file processing jobs.
    Supports multiple concurrent uploads and processing.
    """

    def __init__(self):
        self._jobs: Dict[str, ProcessingJob] = {}
        self._lock = threading.RLock()
        self._cleanup_interval = getattr(settings, 'JOB_CLEANUP_INTERVAL_SECONDS', 3600)  # 1 hour
        self._max_job_age = getattr(settings, 'MAX_JOB_AGE_SECONDS', 7200)  # 2 hours
        self._last_cleanup = time.time()

    def create_job(self, filename: str, user_id: int, job_type: JobType = JobType.UPLOAD) -> str:
        """Create a new processing job and return its ID."""
        job_id = str(uuid.uuid4())
        job = ProcessingJob(
            job_id=job_id,
            filename=filename,
            user_id=user_id,
            status=ProcessingStatus.UPLOADED,
            job_type=job_type,
            created_at=time.time()
        )

        with self._lock:
            self._jobs[job_id] = job
            self._cleanup_old_jobs()

        # Job creation logged at higher level if needed
        return job_id

    def get_job(self, job_id: str) -> Optional[ProcessingJob]:
        """Get a processing job by ID."""
        with self._lock:
            return self._jobs.get(job_id)

    def update_job_status(self, job_id: str, status: ProcessingStatus,
                          message: str = "", progress: float = None,
                          error_message: str = None) -> bool:
        """Update a job's status and return True if successful."""
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return False

            job.status = status
            job.message = message
            if progress is not None:
                job.progress = progress
            if error_message:
                job.error_message = error_message

            # Update timestamps
            if status == ProcessingStatus.PROCESSING and not job.started_at:
                job.started_at = time.time()
            elif status in [ProcessingStatus.COMPLETED, ProcessingStatus.FAILED, ProcessingStatus.CANCELLED]:
                job.completed_at = time.time()

            # Status updates are handled via WebSocket and database logging
            return True

    def set_job_result(self, job_id: str, result_data: Dict[str, Any],
                       import_queue_id: int = None) -> bool:
        """Set the result data for a completed job."""
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return False

            job.result_data = result_data
            job.import_queue_id = import_queue_id
            return True

    def get_user_jobs(self, user_id: int) -> list[ProcessingJob]:
        """Get all jobs for a specific user."""
        with self._lock:
            return [job for job in self._jobs.values() if job.user_id == user_id]

    def get_job_status(self, job_id: str) -> Optional[Dict[str, Any]]:
        """Get job status as a dictionary for API responses."""
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return None

            return {
                'job_id': job.job_id,
                'filename': job.filename,
                'job_type': job.job_type.value,
                'status': job.status.value,
                'progress': job.progress,
                'message': job.message,
                'error_message': job.error_message,
                'created_at': job.created_at,
                'started_at': job.started_at,
                'completed_at': job.completed_at,
                'import_queue_id': job.import_queue_id
            }

    def cancel_job(self, job_id: str) -> bool:
        """Cancel a job if it's not already completed."""
        with self._lock:
            job = self._jobs.get(job_id)
            if not job or job.status in [ProcessingStatus.COMPLETED, ProcessingStatus.FAILED, ProcessingStatus.CANCELLED]:
                return False

            job.status = ProcessingStatus.CANCELLED
            job.completed_at = time.time()
            job.message = "Job cancelled by user"

            # Job cancellation logged at higher level if needed
            return True

    def _cleanup_old_jobs(self):
        """Remove old completed jobs to prevent memory leaks."""
        current_time = time.time()
        if current_time - self._last_cleanup < self._cleanup_interval:
            return

        self._last_cleanup = current_time
        cutoff_time = current_time - self._max_job_age

        jobs_to_remove = []
        for job_id, job in self._jobs.items():
            if (job.status in [ProcessingStatus.COMPLETED, ProcessingStatus.FAILED, ProcessingStatus.CANCELLED]
                    and job.created_at < cutoff_time):
                jobs_to_remove.append(job_id)

        for job_id in jobs_to_remove:
            del self._jobs[job_id]
            # Job cleanup is internal maintenance, no need to log

    def get_stats(self) -> Dict[str, Any]:
        """Get statistics about current jobs."""
        with self._lock:
            total_jobs = len(self._jobs)
            status_counts = {}
            for status in ProcessingStatus:
                status_counts[status.value] = sum(1 for job in self._jobs.values() if job.status == status)

            return {
                'total_jobs': total_jobs,
                'status_counts': status_counts,
                'oldest_job': min((job.created_at for job in self._jobs.values()), default=None),
                'newest_job': max((job.created_at for job in self._jobs.values()), default=None)
            }


# Global instance for the application
status_tracker = ProcessingStatusTracker()
