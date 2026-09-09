import time
import traceback

from django.db import transaction

from api.models import ImportQueue
from geo_lib.importing.draft_store import ImportDraftStore
from geo_lib.importing.runtime import JobPhase, JobRuntime
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.delete import delete_associated_logs
from geo_lib.processing.jobs.helpers.status_tracker import JobType, ProcessingStatus, ProcessingStatusTracker
from geo_lib.processing.jobs.process_job.broadcasting import broadcast_to_import_queue_module
from geo_lib.processing.messages import DELETE_JOB_FAILED
from geo_lib.utils.redis_locks import try_acquire_lock

_logger = get_tagged_logger('DeleteTask')

_CANCEL_WAIT_SECONDS = 30
_CANCEL_POLL = 0.25


def run_delete_job(
    status_tracker: ProcessingStatusTracker,
    job_id: str,
    item_id: int,
    user_id: int,
    filename: str,
    ceiling_seconds: int = 120,
) -> None:
    runtime = JobRuntime(
        status_tracker=status_tracker,
        job_id=job_id,
        user_id=user_id,
        queue_id=item_id,
        phase=JobPhase.DELETE,
        started_at=time.time(),
        ceiling_seconds=ceiling_seconds,
    )
    if runtime.check_cancelled():
        status_tracker.update_job_status(job_id, ProcessingStatus.CANCELED, "Delete canceled")
        return
    try:
        queue = ImportQueue.objects.get(id=item_id, user_id=user_id)
    except ImportQueue.DoesNotExist:
        status_tracker.update_job_status(
            job_id, ProcessingStatus.FAILED, f"Item {item_id} not found",
            error_message=f"Item {item_id} not found",
        )
        return
    status_tracker.update_job_status(job_id, ProcessingStatus.PROCESSING, "Canceling active jobs...", 20.0)
    _cancel_item_jobs(status_tracker, item_id, user_id)
    _wait_for_user_lock(user_id)
    status_tracker.update_job_status(job_id, ProcessingStatus.PROCESSING, "Deleting item...", 80.0)
    try:
        delete_associated_logs(queue, job_id)
        with transaction.atomic():
            ImportDraftStore(queue).clear()
            queue.delete()
    except Exception:
        _logger.error(f"Delete job {job_id} error: {traceback.format_exc()}")
        status_tracker.update_job_status(
            job_id, ProcessingStatus.FAILED, DELETE_JOB_FAILED, error_message=DELETE_JOB_FAILED
        )
        return
    status_tracker.update_job_status(
        job_id, ProcessingStatus.COMPLETED, f"Successfully deleted '{filename}'", 100.0
    )
    broadcast_to_import_queue_module(user_id, 'item_deleted', {'id': item_id})


def _cancel_item_jobs(status_tracker: ProcessingStatusTracker, item_id: int, user_id: int) -> None:
    for job in status_tracker.get_user_jobs(user_id):
        if job.import_queue_id != item_id:
            continue
        if job.job_type not in (JobType.PROCESS, JobType.IMPORT, JobType.RECHECK):
            continue
        if job.status in (ProcessingStatus.PROCESSING, ProcessingStatus.QUEUED):
            status_tracker.cancel_job(job.job_id)
    try:
        queue = ImportQueue.objects.get(id=item_id)
        if queue.queue_status == ImportQueue.STATUS_PROCESSING:
            queue.queue_status = ImportQueue.STATUS_CANCELED
            queue.save(update_fields=['queue_status'])
    except ImportQueue.DoesNotExist:
        pass


def _wait_for_user_lock(user_id: int) -> None:
    deadline = time.time() + _CANCEL_WAIT_SECONDS
    while time.time() < deadline:
        lock = try_acquire_lock(f"import_processing_lock:user:{user_id}", timeout_seconds=5)
        if lock is not None:
            try:
                lock.release()
            except Exception:
                pass
            return
        time.sleep(_CANCEL_POLL)
