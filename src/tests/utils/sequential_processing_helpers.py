"""
Helper utilities for testing sequential processing with Redis queue.

This module provides utilities for:
- Waiting for job completion in tests
- Working with Redis queues in tests
"""

import time
from contextlib import contextmanager
from typing import Optional

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
def with_queue_worker(user_id: int):
    """
    Context manager for working with queue worker in tests.
    
    This requires Redis to be available.
    Use this for integration tests that need to verify queue behavior.
    
    Args:
        user_id: User ID to create worker for
    
    Example:
        with with_queue_worker(user.id):
            # Queue worker is available for this user
            pass
    """
    from geo_lib.processing.queue_worker import WorkerRegistry, stop_all_workers
    
    try:
        yield
    finally:
        # Cleanup workers after test
        stop_all_workers()
        time.sleep(0.5)  # Give workers time to stop


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

