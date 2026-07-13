"""
Cross-process status tracker for asynchronous file processing.

`ProcessJob`'s actual work runs inside Celery worker processes, while jobs are created and
polled from the Django web process (and possibly several of each, across multiple hosts). An
in-memory tracker cannot work in that topology: state has to live somewhere every one of those
processes can see, so Redis (via `redis_job_storage`) is the only store, full stop. This class
adds the typed domain layer on top of it: `ProcessingStatus`/`JobType` enums and a
`ProcessingJob` dataclass instead of raw JSON dicts.
"""

import time
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Dict, Optional, Any, List

from geo_lib.processing.jobs.helpers import redis_job_storage


class ProcessingStatus(Enum):
    """Status of file processing."""
    QUEUED = "queued"  # File uploaded, waiting to start processing
    PROCESSING = "processing"  # Currently being processed
    COMPLETED = "completed"  # Processing completed successfully
    FAILED = "failed"  # Processing failed
    CANCELED = "canceled"  # Processing was canceled


class JobType(Enum):
    """Type of job being processed."""
    PROCESS = "process"  # File processing job (converting to geojson)
    IMPORT = "import"  # Importing a single item to the feature store
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
    import_queue_id: Optional[int] = None


def _job_from_redis_data(data: Dict[str, Any]) -> ProcessingJob:
    return ProcessingJob(
        job_id=data['job_id'],
        filename=data['filename'],
        user_id=data['user_id'],
        status=ProcessingStatus(data['status']),
        job_type=JobType(data['job_type']),
        created_at=data['created_at'],
        started_at=data.get('started_at'),
        completed_at=data.get('completed_at'),
        progress=data.get('progress', 0.0),
        message=data.get('message', ''),
        error_message=data.get('error_message'),
        import_queue_id=data.get('import_queue_id'),
    )


class ProcessingStatusTracker:
    """
    Redis-backed tracker for background processing jobs.

    Safe to share across the Django web process and any number of Celery worker processes:
    every method reads/writes straight through to Redis, so job state is always visible
    cross-process, with no in-memory caching to go stale or fall out of sync.
    """

    def create_job(self, filename: str, user_id: int, job_type: JobType = JobType.PROCESS) -> str:
        """Create a new processing job and return its ID."""
        job_id = str(uuid.uuid4())
        redis_job_storage.store_job_started(
            job_id=job_id,
            user_id=user_id,
            job_type=job_type.value,
            filename=filename,
            created_at=time.time(),
        )
        return job_id

    def get_job(self, job_id: str) -> Optional[ProcessingJob]:
        """Get a processing job by ID."""
        data = redis_job_storage.get_job_status(job_id)
        return _job_from_redis_data(data) if data else None

    def update_job_status(self, job_id: str, status: ProcessingStatus,
                          message: str = "", progress: float = None,
                          error_message: str = None) -> bool:
        """Update a job's status and return True if successful."""
        existing = redis_job_storage.get_job_status(job_id)
        if not existing:
            return False

        started_at = existing.get('started_at')
        completed_at = existing.get('completed_at')
        if status == ProcessingStatus.PROCESSING and not started_at:
            started_at = time.time()
        elif status in (ProcessingStatus.COMPLETED, ProcessingStatus.FAILED, ProcessingStatus.CANCELED):
            completed_at = time.time()

        return redis_job_storage.update_job_status(
            job_id, status.value, message=message, progress=progress, error_message=error_message,
            started_at=started_at, completed_at=completed_at,
        )

    def set_job_import_queue_id(self, job_id: str, import_queue_id: int) -> bool:
        """Attach the owning ImportQueue row ID to a job."""
        existing = redis_job_storage.get_job_status(job_id)
        if not existing:
            return False
        return redis_job_storage.update_job_status(
            job_id, existing['status'], import_queue_id=import_queue_id,
        )

    def get_user_jobs(self, user_id: int) -> List[ProcessingJob]:
        """Get all jobs for a specific user."""
        return [_job_from_redis_data(data) for data in redis_job_storage.get_user_jobs(user_id)]

    def get_job_status(self, job_id: str) -> Optional[Dict[str, Any]]:
        """Get job status as a dictionary for API responses."""
        data = redis_job_storage.get_job_status(job_id)
        if not data:
            return None

        return {
            'job_id': data['job_id'],
            'filename': data['filename'],
            'job_type': data['job_type'],
            'status': data['status'],
            'progress': data.get('progress', 0.0),
            'message': data.get('message', ''),
            'error_message': data.get('error_message'),
            'created_at': data['created_at'],
            'started_at': data.get('started_at'),
            'completed_at': data.get('completed_at'),
            'import_queue_id': data.get('import_queue_id'),
        }

    def cancel_job(self, job_id: str) -> bool:
        """Cancel a job if it's not already completed."""
        existing = redis_job_storage.get_job_status(job_id)
        if not existing:
            return False
        if existing['status'] in (ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value,
                                   ProcessingStatus.CANCELED.value):
            return False

        return redis_job_storage.update_job_status(
            job_id, ProcessingStatus.CANCELED.value,
            message="Job canceled by user", completed_at=time.time(),
        )


# Global instance for the application
status_tracker = ProcessingStatusTracker()
