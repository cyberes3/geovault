"""
Low-level Redis storage for background job status.

This module is intentionally unaware of `ProcessingStatus`/`JobType` (see
`geo_lib.processing.jobs.helpers.status_tracker`): it stores whatever plain JSON-serializable
dict it's given, keyed by job ID, so it has no dependency on the higher-level tracker that
wraps it. This is the single source of truth for job status: it must be readable from every
process that might touch a job (the Django web process and any number of Celery worker
processes), so nothing here may be cached in memory.
"""

import json
from typing import Dict, Any, Optional, List

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.redis_connection import get_redis_connection
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()

# TTL for jobs that reached a terminal state (completed/failed/canceled): 10 minutes.
# Short-lived, since the frontend only needs to observe the final state briefly after polling.
COMPLETED_JOB_TTL = 600

_TERMINAL_STATUS_VALUES = frozenset({'completed', 'failed', 'canceled'})


def _active_job_ttl() -> int:
    """
    TTL applied to jobs still queued/processing, refreshed on every update.

    This is a self-healing ceiling, not an expected lifetime: a job that's actively
    progressing gets its TTL refreshed well before this elapses, so it only ever kicks in if
    something (e.g. a killed worker) stops updating a job's status entirely.
    """
    return get_required_setting('MAX_JOB_AGE_SECONDS')


def _get_job_key(job_id: str) -> str:
    """Get Redis key for job data."""
    return f"job:{job_id}"


def _get_user_jobs_key(user_id: int) -> str:
    """Get Redis key for user's job IDs set."""
    return f"user_jobs:{user_id}"


def store_job_started(job_id: str, user_id: int, job_type: str, filename: str,
                      created_at: float, **kwargs) -> bool:
    """
    Store job information when it starts.
    
    Args:
        job_id: Unique job identifier
        user_id: ID of the user who owns the job
        job_type: Type of job (import, delete, bulk_import, bulk_delete, process)
        filename: Name of the file or description
        created_at: Timestamp when job was created
        **kwargs: Additional job metadata
        
    Returns:
        True if stored successfully, False otherwise
    """
    try:
        redis_client = get_redis_connection()

        job_data = {
            'job_id': job_id,
            'user_id': user_id,
            'job_type': job_type,
            'filename': filename,
            'status': 'queued',
            'progress': 0.0,
            'message': '',
            'error_message': None,
            'created_at': created_at,
            'started_at': None,
            'completed_at': None,
            **kwargs
        }

        redis_client.setex(_get_job_key(job_id), _active_job_ttl(), json.dumps(job_data))
        redis_client.sadd(_get_user_jobs_key(user_id), job_id)

        return True
    except Exception as e:
        _logger.error(f"Failed to store job started in Redis: {e}")
        return False


def update_job_status(job_id: str, status: str, message: str = "",
                      progress: Optional[float] = None, error_message: Optional[str] = None,
                      started_at: Optional[float] = None, completed_at: Optional[float] = None,
                      **kwargs) -> bool:
    """
    Update job status in Redis.
    
    Args:
        job_id: Unique job identifier
        status: Current job status value (e.g. ProcessingStatus.PROCESSING.value)
        message: Status message
        progress: Progress percentage (0-100)
        error_message: Error message if failed
        started_at: Timestamp when job started processing
        completed_at: Timestamp when job completed/failed
        **kwargs: Additional metadata to update
        
    Returns:
        True if updated successfully, False otherwise
    """
    redis_client = get_redis_connection()
    job_key = _get_job_key(job_id)

    existing_data = redis_client.get(job_key)
    if not existing_data:
        _logger.warning(f"Job {job_id} not found in Redis for status update")
        return False

    job_data = json.loads(existing_data)

    job_data['status'] = status
    if message:
        job_data['message'] = message
    if progress is not None:
        job_data['progress'] = progress
    if error_message is not None:
        job_data['error_message'] = error_message
    if started_at is not None:
        job_data['started_at'] = started_at
    if completed_at is not None:
        job_data['completed_at'] = completed_at

    job_data.update(kwargs)

    ttl = COMPLETED_JOB_TTL if status in _TERMINAL_STATUS_VALUES else _active_job_ttl()
    redis_client.setex(job_key, ttl, json.dumps(job_data))

    return True


def get_job_status(job_id: str) -> Optional[Dict[str, Any]]:
    """
    Get job status from Redis.
    
    Args:
        job_id: Unique job identifier
        
    Returns:
        Job data dictionary or None if not found
    """
    redis_client = get_redis_connection()
    job_key = _get_job_key(job_id)

    job_data = redis_client.get(job_key)
    if not job_data:
        return None

    return json.loads(job_data)


def get_user_jobs(user_id: int) -> List[Dict[str, Any]]:
    """
    Get all jobs for a specific user from Redis.
    
    Args:
        user_id: ID of the user
        
    Returns:
        List of job data dictionaries
    """
    redis_client = get_redis_connection()
    user_jobs_key = _get_user_jobs_key(user_id)

    job_ids = redis_client.smembers(user_jobs_key)
    if not job_ids:
        return []

    jobs = []
    for job_id in job_ids:
        job_data = get_job_status(job_id)
        if job_data:
            jobs.append(job_data)
        else:
            # Job expired or was deleted, remove from set
            redis_client.srem(user_jobs_key, job_id)

    jobs.sort(key=lambda x: x.get('created_at', 0), reverse=True)

    return jobs
