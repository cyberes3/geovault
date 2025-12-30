"""
Job recovery system for interrupted processing jobs.

When the server restarts, Redis queues are cleared but ImportQueue entries
persist in the database. This module provides functionality to recover and
re-enqueue jobs that were interrupted during processing.

Note: File data is stored in the database (ImportQueue.raw_file), not in Redis.
Redis only contains job metadata for queuing.
"""
import traceback
from typing import Dict, Any

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.jobs.process_job import ProcessJob
from geo_lib.processing.queue_worker import start_worker_for_user
from geo_lib.processing.redis_queue import get_processing_queue

_logger = get_tagged_logger('JobRecovery')


def recover_interrupted_jobs() -> Dict[str, Any]:
    """
    Recover jobs that were interrupted during processing when the server was stopped.
    
    This function:
    1. Finds ImportQueue entries that have raw_file data but no geofeatures
       (indicating they were being processed but didn't complete)
    2. Re-enqueues them to the Redis processing queue
    3. Starts workers for affected users
    
    Returns:
        Dictionary with recovery statistics:
        - total_found: Number of interrupted jobs found
        - recovered: Number of jobs successfully re-enqueued
        - failed: Number of jobs that failed to recover
        - users_affected: Number of unique users with recovered jobs
    """
    _logger.info("Starting job recovery check...")

    # Find ImportQueue entries that were being processed but didn't complete
    # Conditions:
    # - Has raw_file content (file was uploaded and saved)
    # - geofeatures is empty list (processing not completed)
    # - Not marked as unparsable (wasn't a failed parse)
    # - Not imported (wasn't successfully imported to feature store)
    interrupted_jobs = ImportQueue.objects.filter(
        unparsable=False,
        imported=False
    ).exclude(
        raw_file=''
    )

    # Further filter to only include items where geofeatures is truly empty
    # (not just an empty array but actually no processing results)
    interrupted_jobs = [
        job for job in interrupted_jobs
        if not job.geofeatures or (isinstance(job.geofeatures, list) and len(job.geofeatures) == 0)
    ]

    total_found = len(interrupted_jobs)

    if total_found == 0:
        _logger.info("✓ No interrupted jobs found - all jobs completed successfully")
        return {
            'total_found': 0,
            'recovered': 0,
            'failed': 0,
            'users_affected': 0
        }

    _logger.info(f"Found {total_found} interrupted job(s) to recover")

    recovered = 0
    failed = 0
    users_affected = set()

    for job in interrupted_jobs:
        try:
            # Re-enqueue the job
            success = _reenqueue_job(job)

            if success:
                recovered += 1
                users_affected.add(job.user_id)
                _logger.info(f"✓ Recovered job: {job.original_filename} (ID: {job.id}) for user {job.user_id}")
            else:
                failed += 1
                _logger.warning(f"✗ Failed to recover job: {job.original_filename} (ID: {job.id})")

        except:
            failed += 1
            _logger.error(f"✗ Error recovering job {job.id}: {traceback.format_exc()}", exc_info=True)

    _logger.info(f"Job recovery complete: {recovered}/{total_found} jobs recovered, {len(users_affected)} user(s) affected")

    return {
        'total_found': total_found,
        'recovered': recovered,
        'failed': failed,
        'users_affected': len(users_affected)
    }


def _reenqueue_job(import_queue_entry: ImportQueue) -> bool:
    """
    Re-enqueue a single interrupted job.
    
    Args:
        import_queue_entry: ImportQueue entry to re-enqueue
        
    Returns:
        True if successfully re-enqueued, False otherwise
    """
    # Verify raw_file exists in database (it should, since we filtered for it)
    if not import_queue_entry.raw_file:
        _logger.warning(f"Job {import_queue_entry.id} has no raw_file data, skipping")
        return False

    # Create a new job ID
    job_id = status_tracker.create_job(
        import_queue_entry.original_filename,
        import_queue_entry.user_id
    )

    # Update the import queue entry to reference this new job
    status_tracker.set_job_result(job_id, {}, import_queue_entry.id)

    # Enqueue job to Redis (only metadata - file data is already in database)
    queue = get_processing_queue(import_queue_entry.user_id)
    job_data = {
        'job_id': job_id,
        'import_queue_id': import_queue_entry.id,
        'filename': import_queue_entry.original_filename,
        'user_id': import_queue_entry.user_id,
        'timestamp': import_queue_entry.timestamp.timestamp(),
        'replacement_feature_id': import_queue_entry.replacement
    }

    success = queue.enqueue(job_data)

    if not success:
        _logger.error(f"Failed to enqueue recovered job {job_id} for user {import_queue_entry.user_id}")
        return False

    # Start worker for this user
    process_job = ProcessJob(status_tracker)
    start_worker_for_user(import_queue_entry.user_id, process_job)

    return True


def get_interrupted_jobs_count() -> int:
    """
    Get the count of interrupted jobs without recovering them.
    
    Returns:
        Number of interrupted jobs
    """
    interrupted_jobs = ImportQueue.objects.filter(
        unparsable=False,
        imported=False
    ).exclude(
        raw_file=''
    )

    # Filter for truly empty geofeatures
    interrupted_jobs = [
        job for job in interrupted_jobs
        if not job.geofeatures or (isinstance(job.geofeatures, list) and len(job.geofeatures) == 0)
    ]

    return len(interrupted_jobs)
