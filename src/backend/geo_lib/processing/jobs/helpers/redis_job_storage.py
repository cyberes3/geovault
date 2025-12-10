"""
Redis-based storage for background job status.
Provides persistent storage for job status that can be queried via API.
"""

import json
from typing import Dict, Any, Optional, List

from geo_lib.logging.console import get_job_logger
from geo_lib.processing.status_tracker import ProcessingStatus
from geo_lib.utils.redis_connection import get_redis_connection

_logger = get_job_logger()

# TTL for completed/failed jobs: 10 minutes
COMPLETED_JOB_TTL = 600


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
        job_type: Type of job (import, delete, bulk_import, bulk_delete)
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
            'status': ProcessingStatus.QUEUED.value,
            'progress': 0.0,
            'message': '',
            'error_message': None,
            'created_at': created_at,
            'started_at': None,
            'completed_at': None,
            **kwargs
        }

        # Store job data
        redis_client.set(_get_job_key(job_id), json.dumps(job_data))

        # Add to user's job set
        redis_client.sadd(_get_user_jobs_key(user_id), job_id)

        return True
    except Exception as e:
        _logger.error(f"Failed to store job started in Redis: {e}")
        return False


def update_job_status(job_id: str, status: ProcessingStatus, message: str = "",
                      progress: Optional[float] = None, error_message: Optional[str] = None,
                      started_at: Optional[float] = None, completed_at: Optional[float] = None,
                      **kwargs) -> bool:
    """
    Update job status in Redis.
    
    Args:
        job_id: Unique job identifier
        status: Current job status
        message: Status message
        progress: Progress percentage (0-100)
        error_message: Error message if failed
        started_at: Timestamp when job started processing
        completed_at: Timestamp when job completed/failed
        **kwargs: Additional metadata to update
        
    Returns:
        True if updated successfully, False otherwise
    """
    try:
        redis_client = get_redis_connection()
        job_key = _get_job_key(job_id)

        # Get existing job data
        existing_data = redis_client.get(job_key)
        if not existing_data:
            _logger.warning(f"Job {job_id} not found in Redis for status update")
            return False

        job_data = json.loads(existing_data)

        # Update fields
        job_data['status'] = status.value
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

        # Update any additional fields
        job_data.update(kwargs)

        # Determine TTL: set 10-minute TTL for completed/failed jobs
        ttl = None
        if status in [ProcessingStatus.COMPLETED, ProcessingStatus.FAILED, ProcessingStatus.CANCELLED]:
            ttl = COMPLETED_JOB_TTL

        # Update job data
        if ttl:
            redis_client.setex(job_key, ttl, json.dumps(job_data))
        else:
            redis_client.set(job_key, json.dumps(job_data))

        return True
    except Exception as e:
        _logger.error(f"Failed to update job status in Redis: {e}")
        return False


def get_job_status(job_id: str) -> Optional[Dict[str, Any]]:
    """
    Get job status from Redis.
    
    Args:
        job_id: Unique job identifier
        
    Returns:
        Job data dictionary or None if not found
    """
    try:
        redis_client = get_redis_connection()
        job_key = _get_job_key(job_id)

        job_data = redis_client.get(job_key)
        if not job_data:
            return None

        return json.loads(job_data)
    except Exception as e:
        _logger.error(f"Failed to get job status from Redis: {e}")
        return None


def get_user_jobs(user_id: int) -> List[Dict[str, Any]]:
    """
    Get all jobs for a specific user from Redis.
    
    Args:
        user_id: ID of the user
        
    Returns:
        List of job data dictionaries
    """
    try:
        redis_client = get_redis_connection()
        user_jobs_key = _get_user_jobs_key(user_id)

        # Get all job IDs for this user
        job_ids = redis_client.smembers(user_jobs_key)
        if not job_ids:
            return []

        # Fetch all job data
        jobs = []
        for job_id in job_ids:
            job_data = get_job_status(job_id)
            if job_data:
                jobs.append(job_data)
            else:
                # Job expired or was deleted, remove from set
                redis_client.srem(user_jobs_key, job_id)

        # Sort by created_at descending (newest first)
        jobs.sort(key=lambda x: x.get('created_at', 0), reverse=True)

        return jobs
    except Exception as e:
        _logger.error(f"Failed to get user jobs from Redis: {e}")
        return []
