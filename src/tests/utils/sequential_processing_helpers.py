"""
Helper utilities for testing sequential processing with RedisProcessingLock.

This module provides utilities for:
- Waiting for job completion in tests
- Acquiring Redis locks in tests
- Mocking Redis lock behavior
"""

import time
from contextlib import contextmanager
from typing import Optional
from unittest.mock import MagicMock, patch

from geo_lib.processing.status_tracker import ProcessingStatus, status_tracker


def wait_for_job_completion(job_id: str, timeout: float = 10.0, poll_interval: float = 0.1) -> bool:
    """
    Wait for a job to reach a terminal state (COMPLETED or FAILED).
    
    Args:
        job_id: Job ID to wait for
        timeout: Maximum time to wait in seconds (default: 10)
        poll_interval: Time between status checks in seconds (default: 0.1)
    
    Returns:
        True if job completed successfully, False if failed or timed out
    
    Raises:
        TimeoutError: If job doesn't complete within timeout
    """
    start_time = time.time()
    
    while time.time() - start_time < timeout:
        job = status_tracker.get_job(job_id)
        
        if not job:
            raise ValueError(f"Job {job_id} not found")
        
        if job.status == ProcessingStatus.COMPLETED:
            return True
        elif job.status == ProcessingStatus.FAILED:
            return False
        elif job.status == ProcessingStatus.CANCELLED:
            return False
        
        time.sleep(poll_interval)
    
    # Timeout reached
    job = status_tracker.get_job(job_id)
    current_status = job.status if job else "not found"
    raise TimeoutError(
        f"Job {job_id} did not complete within {timeout}s (current status: {current_status})"
    )


def wait_for_job_status(job_id: str, expected_status: ProcessingStatus, 
                        timeout: float = 10.0, poll_interval: float = 0.1) -> bool:
    """
    Wait for a job to reach a specific status.
    
    Args:
        job_id: Job ID to wait for
        expected_status: Status to wait for
        timeout: Maximum time to wait in seconds (default: 10)
        poll_interval: Time between status checks in seconds (default: 0.1)
    
    Returns:
        True if job reached expected status, False if timed out
    """
    start_time = time.time()
    
    while time.time() - start_time < timeout:
        job = status_tracker.get_job(job_id)
        
        if not job:
            raise ValueError(f"Job {job_id} not found")
        
        if job.status == expected_status:
            return True
        
        time.sleep(poll_interval)
    
    return False


@contextmanager
def with_redis_lock(user_id: int, job_id: Optional[str] = None):
    """
    Context manager for acquiring Redis lock in tests.
    
    This uses the real RedisProcessingLock, so it requires Redis to be available.
    Use this for integration tests that need to verify lock behavior.
    
    Args:
        user_id: User ID to create lock for
        job_id: Optional job ID for status updates
    
    Example:
        with with_redis_lock(user.id):
            # Code here runs with lock held
            pass
    """
    from geo_lib.utils.redis_lock import RedisProcessingLock
    
    lock = RedisProcessingLock(user_id, job_id, status_tracker)
    with lock:
        yield lock


@contextmanager
def mock_redis_lock(acquired: bool = True, wait_time: float = 0.0):
    """
    Mock RedisProcessingLock for unit tests that don't need real Redis.
    
    Args:
        acquired: Whether the lock should be successfully acquired (default: True)
        wait_time: Simulated wait time before acquiring lock (default: 0)
    
    Example:
        with mock_redis_lock():
            # Code using RedisProcessingLock will use the mock
            process_job.start_process_job(...)
    """
    mock_lock_instance = MagicMock()
    
    def mock_enter(self):
        if wait_time > 0:
            time.sleep(wait_time)
        if not acquired:
            raise TimeoutError("Mock lock timeout")
        return self
    
    def mock_exit(self, exc_type, exc_val, exc_tb):
        return False
    
    mock_lock_instance.__enter__ = mock_enter
    mock_lock_instance.__exit__ = mock_exit
    
    with patch('geo_lib.utils.redis_lock.RedisProcessingLock', return_value=mock_lock_instance):
        yield mock_lock_instance


def ensure_job_thread_completes(job_id: str, timeout: float = 5.0):
    """
    Ensure that the background thread for a job has completed.
    
    This is useful for tests that need to verify job completion without
    checking the status tracker (e.g., when testing thread cleanup).
    
    Args:
        job_id: Job ID
        timeout: Maximum time to wait for thread completion
    """
    from geo_lib.processing.jobs import process_job, import_job, delete_job
    
    # Check all job types for the thread
    for job_processor in [process_job, import_job, delete_job]:
        if job_id in job_processor._active_threads:
            thread = job_processor._active_threads[job_id]
            thread.join(timeout=timeout)
            if thread.is_alive():
                raise TimeoutError(f"Job thread {job_id} did not complete within {timeout}s")
            break


def get_job_import_queue_id(job_id: str) -> Optional[int]:
    """
    Get the import queue ID associated with a job.
    
    Args:
        job_id: Job ID
    
    Returns:
        Import queue ID or None if not found
    """
    job = status_tracker.get_job(job_id)
    return job.import_queue_id if job else None


def assert_job_completed_successfully(job_id: str, timeout: float = 10.0):
    """
    Assert that a job completed successfully within the timeout.
    
    Args:
        job_id: Job ID
        timeout: Maximum time to wait
    
    Raises:
        AssertionError: If job failed or timed out
        TimeoutError: If job didn't complete within timeout
    """
    completed = wait_for_job_completion(job_id, timeout)
    assert completed, f"Job {job_id} failed"


def assert_job_failed(job_id: str, timeout: float = 10.0):
    """
    Assert that a job failed within the timeout.
    
    Args:
        job_id: Job ID
        timeout: Maximum time to wait
    
    Raises:
        AssertionError: If job completed successfully
        TimeoutError: If job didn't reach terminal state within timeout
    """
    completed = wait_for_job_completion(job_id, timeout)
    assert not completed, f"Job {job_id} unexpectedly succeeded"

