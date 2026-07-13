"""
Job recovery system for interrupted processing jobs.

File data lives in the database (`ImportQueue.raw_file`), never only in Redis or a Celery
broker, so an `ImportQueue` row with no result yet is always recoverable after a restart: this
module finds such rows and redispatches them to the `imports` Celery queue.
"""
import traceback
from typing import Dict, Any

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.job_ceiling import calculate_job_ceiling_seconds
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.jobs.process_job import dispatch_import_job
from geo_lib.utils.redis_locks import try_acquire_lock

_logger = get_tagged_logger('JobRecovery')

# How long a single ImportQueue row's recovery-dispatch lock is held. Only needs to outlive one
# `recover_interrupted_jobs()` run; it exists purely to make that run idempotent if it's ever
# triggered concurrently (e.g. overlapping deploys), not to guard against slow processing.
_RECOVERY_DISPATCH_LOCK_SECONDS = 300


def recover_interrupted_jobs() -> Dict[str, Any]:
    """
    Recover jobs that were interrupted during processing when the server was stopped.

    This function:
    1. Finds ImportQueue entries that have raw_file data but no geofeatures
       (indicating they were being processed but didn't complete)
    2. Redispatches each to the `imports` Celery queue under a new job ID

    Returns:
        Dictionary with recovery statistics:
        - total_found: Number of interrupted jobs found
        - recovered: Number of jobs successfully redispatched
        - failed: Number of jobs that failed to recover
        - users_affected: Number of unique users with recovered jobs
    """
    _logger.info("Starting job recovery check...")

    interrupted_jobs = _find_interrupted_jobs()
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
            if _redispatch_job(job):
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


def _find_interrupted_jobs():
    """
    Find ImportQueue entries that were being processed but didn't complete.

    Conditions:
    - Has raw_file content (file was uploaded and saved)
    - geofeatures is empty (processing not completed)
    - Not marked as unparsable (wasn't a failed parse)
    - Not imported (wasn't successfully imported to feature store)
    """
    candidates = ImportQueue.objects.filter(
        unparsable=False,
        imported=False
    ).exclude(
        raw_file=''
    )
    return [
        job for job in candidates
        if not job.geofeatures or (isinstance(job.geofeatures, list) and len(job.geofeatures) == 0)
    ]


def _redispatch_job(import_queue_entry: ImportQueue) -> bool:
    """
    Redispatch a single interrupted job to the `imports` Celery queue.

    Guarded by a short-lived Redis lock keyed by the ImportQueue row's ID: if two recovery
    runs somehow overlap (e.g. two web processes both run startup checks at once), only one of
    them dispatches a Celery task for a given row, so the job never gets processed twice.
    """
    if not import_queue_entry.raw_file:
        _logger.warning(f"Job {import_queue_entry.id} has no raw_file data, skipping")
        return False

    # Deliberately never released: it should stay held for its full TTL so a concurrent
    # recovery run can't dispatch a second Celery task for this row later in the same pass.
    lock = try_acquire_lock(
        f"import_recovery_dispatch_lock:import_queue:{import_queue_entry.id}",
        timeout_seconds=_RECOVERY_DISPATCH_LOCK_SECONDS,
    )
    if lock is None:
        _logger.info(f"Job {import_queue_entry.id} is already being redispatched by another process, skipping")
        return False

    job_id = status_tracker.create_job(
        import_queue_entry.original_filename,
        import_queue_entry.user_id
    )
    status_tracker.set_job_import_queue_id(job_id, import_queue_entry.id)

    job_ceiling_seconds = calculate_job_ceiling_seconds(len(import_queue_entry.raw_file.encode('utf-8')))
    job_data = {
        'job_id': job_id,
        'import_queue_id': import_queue_entry.id,
        'filename': import_queue_entry.original_filename,
        'user_id': import_queue_entry.user_id,
        'timestamp': import_queue_entry.timestamp.timestamp(),
        'replacement_feature_id': import_queue_entry.replacement,
        'job_ceiling_seconds': job_ceiling_seconds,
    }
    dispatch_import_job(job_id, job_data)
    return True


def get_interrupted_jobs_count() -> int:
    """
    Get the count of interrupted jobs without recovering them.

    Returns:
        Number of interrupted jobs
    """
    return len(_find_interrupted_jobs())
