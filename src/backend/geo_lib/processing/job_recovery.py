"""
Job recovery for interrupted process jobs.

Only `queue_status=processing` rows with no draft features and an expired per-user
lock are redispatched. Canceled, failed, ready, and imported rows are never recovered.
"""
import traceback
from typing import Any, Dict

from django.db.models import Count

from api.models import ImportQueue
from geo_lib.importing.jobs.user_lock import is_user_import_lock_held
from geo_lib.importing.session import RawFile
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.job_ceiling import calculate_job_ceiling_seconds
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.jobs.process_job.dispatch import dispatch_import_job
from geo_lib.utils.redis_locks import try_acquire_lock

_logger = get_tagged_logger('JobRecovery')

_RECOVERY_DISPATCH_LOCK_SECONDS = 300


def recover_interrupted_jobs() -> Dict[str, Any]:
    _logger.info("Starting job recovery check...")
    interrupted_jobs = _find_interrupted_jobs()
    total_found = len(interrupted_jobs)
    if total_found == 0:
        _logger.info("No interrupted jobs found")
        return {
            'total_found': 0,
            'recovered': 0,
            'failed': 0,
            'users_affected': 0
        }

    recovered = 0
    failed = 0
    users_affected = set()
    for job in interrupted_jobs:
        try:
            if _redispatch_job(job):
                recovered += 1
                users_affected.add(job.user_id)
                _logger.info(f"Recovered job: {job.original_filename} (ID: {job.id}) for user {job.user_id}")
            else:
                failed += 1
                _logger.warning(f"Failed to recover job: {job.original_filename} (ID: {job.id})")
        except Exception:
            failed += 1
            _logger.error(f"Error recovering job {job.id}: {traceback.format_exc()}", exc_info=True)

    return {
        'total_found': total_found,
        'recovered': recovered,
        'failed': failed,
        'users_affected': len(users_affected)
    }


def _find_interrupted_jobs():
    candidates = ImportQueue.objects.filter(
        queue_status=ImportQueue.STATUS_PROCESSING,
        imported=False,
        unparsable=False,
    ).exclude(
        raw_file='',
    ).annotate(
        draft_count=Count('draft_features'),
    ).filter(
        draft_count=0,
    )
    return [job for job in candidates if not is_user_import_lock_held(job.user_id)]


def _redispatch_job(import_queue_entry: ImportQueue) -> bool:
    if not import_queue_entry.raw_file:
        _logger.warning(f"Job {import_queue_entry.id} has no raw_file data, skipping")
        return False
    lock = try_acquire_lock(
        f"import_recovery_dispatch_lock:import_queue:{import_queue_entry.id}",
        timeout_seconds=_RECOVERY_DISPATCH_LOCK_SECONDS,
    )
    if lock is None:
        _logger.info(f"Job {import_queue_entry.id} is already being redispatched, skipping")
        return False
    job_id = status_tracker.create_job(
        import_queue_entry.original_filename,
        import_queue_entry.user_id
    )
    status_tracker.set_job_import_queue_id(job_id, import_queue_entry.id)
    raw = RawFile.load(
        import_queue_entry.raw_file,
        import_queue_entry.raw_file_encoding,
        import_queue_entry.file_hash,
    )
    job_ceiling_seconds = calculate_job_ceiling_seconds(len(raw.data))
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
    return len(_find_interrupted_jobs())
