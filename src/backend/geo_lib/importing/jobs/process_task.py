import time
import traceback

from celery.exceptions import SoftTimeLimitExceeded
from django.conf import settings

from api.models import ImportQueue
from geo_lib.importing.errors import ImportCancelled, ImportStageError, ImportTimeout
from geo_lib.importing.pipeline import ImportPipeline
from geo_lib.importing.runtime import JobPhase, JobRuntime
from geo_lib.importing.session import ProcessOptions, UploadSession
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, ProcessingStatusTracker
from geo_lib.processing.jobs.process_job.broadcasting import (
    broadcast_to_import_queue_module,
    broadcast_to_process_status_module,
)
from geo_lib.processing.logging import DatabaseLogLevel, RealTimeImportLog
from geo_lib.processing.messages import (
    ERROR_OCCURRED_DURING_PROCESSING,
    FILE_VALIDATION_FAILED,
    PROCESSING_FAILED,
    PROCESSING_TIMEOUT,
)
from geo_lib.security.exceptions import FileValidationError, SecurityError

_logger = get_tagged_logger('ProcessTask')


def run_process_job(
    status_tracker: ProcessingStatusTracker,
    job_id: str,
    job_data: dict,
) -> None:
    user_id = job_data['user_id']
    job = status_tracker.get_job(job_id)
    if not job or not job.import_queue_id:
        _logger.error(f"Process job {job_id} not found or missing import_queue_id")
        return
    queue_id = job.import_queue_id
    try:
        queue = ImportQueue.objects.get(id=queue_id)
    except ImportQueue.DoesNotExist:
        return
    if queue.queue_status in (ImportQueue.STATUS_CANCELED, ImportQueue.STATUS_FAILED, ImportQueue.STATUS_IMPORTED):
        _logger.info(f"Queue {queue_id} is terminal ({queue.queue_status}); skipping process")
        return
    queue.queue_status = ImportQueue.STATUS_PROCESSING
    queue.save(update_fields=['queue_status'])
    session = UploadSession.from_queue(
        queue,
        ProcessOptions(minimal_processing=queue.replacement is not None),
    )
    realtime_log = RealTimeImportLog(user_id, queue.log_id) if queue.log_id else None
    started_at = time.time()
    runtime = JobRuntime(
        status_tracker=status_tracker,
        job_id=job_id,
        user_id=user_id,
        queue_id=queue_id,
        phase=JobPhase.PROCESS,
        started_at=started_at,
        ceiling_seconds=job_data['job_ceiling_seconds'],
    )
    if runtime.check_cancelled():
        _mark_canceled(status_tracker, job_id, queue, realtime_log, 'before process')
        return
    try:
        ImportPipeline().process(session, runtime)
    except ImportCancelled as exc:
        _mark_canceled(status_tracker, job_id, queue, realtime_log, exc.stage)
        return
    except (ImportTimeout, TimeoutError, SoftTimeLimitExceeded):
        _mark_failed(status_tracker, job_id, queue, user_id, PROCESSING_TIMEOUT, realtime_log)
        return
    except ImportStageError as exc:
        _mark_failed(status_tracker, job_id, queue, user_id, exc.message, realtime_log, unparsable=exc.unparsable)
        return
    except (SecurityError, FileValidationError) as exc:
        _mark_failed(
            status_tracker, job_id, queue, user_id,
            f"{FILE_VALIDATION_FAILED}: {exc}", realtime_log, unparsable=True,
        )
        return
    except Exception as exc:
        if _is_shutdown(exc):
            _logger.info(f"Job {job_id} interrupted by server shutdown")
            return
        _logger.error(f"Upload processing error for job {job_id}: {traceback.format_exc()}")
        message = _user_error_message(exc)
        _mark_failed(status_tracker, job_id, queue, user_id, message, realtime_log)
        return

    duration = time.time() - started_at
    queue.refresh_from_db()
    feature_count = queue.draft_features.count()
    completion = f"File processing completed! Processed {feature_count} features in {duration:.1f}s"
    status_tracker.update_job_status(job_id, ProcessingStatus.COMPLETED, completion, 100.0)
    broadcast_to_import_queue_module(user_id, 'status_updated', {
        'id': queue_id,
        'status': 'ready',
        'progress': 100.0,
        'message': 'Processing completed',
    })
    broadcast_to_process_status_module(user_id, queue_id, 'item_completed', {
        'job_id': job_id,
        'message': completion,
    })
    if realtime_log:
        realtime_log.add(completion, "ProcessJob", DatabaseLogLevel.INFO)
    _logger.info(f"Job {job_id} completed: {feature_count} features in {duration:.2f}s")


def _mark_canceled(
    status_tracker: ProcessingStatusTracker,
    job_id: str,
    queue: ImportQueue,
    realtime_log: RealTimeImportLog | None,
    stage: str,
) -> None:
    queue.queue_status = ImportQueue.STATUS_CANCELED
    queue.save(update_fields=['queue_status'])
    status_tracker.update_job_status(
        job_id, ProcessingStatus.CANCELED, f"Processing canceled {stage}", error_message="canceled"
    )
    if realtime_log:
        realtime_log.add(f"Processing canceled {stage}", "ProcessJob", DatabaseLogLevel.WARNING)
    broadcast_to_import_queue_module(queue.user_id, 'status_updated', {
        'id': queue.id,
        'status': 'canceled',
        'progress': 0.0,
        'message': 'Canceled',
    })


def _mark_failed(
    status_tracker: ProcessingStatusTracker,
    job_id: str,
    queue: ImportQueue,
    user_id: int,
    error_msg: str,
    realtime_log: RealTimeImportLog | None,
    unparsable: bool = False,
) -> None:
    queue.queue_status = ImportQueue.STATUS_FAILED
    queue.unparsable = unparsable or queue.unparsable
    queue.save(update_fields=['queue_status', 'unparsable'])
    if realtime_log:
        realtime_log.add(error_msg, "ProcessJob", DatabaseLogLevel.ERROR)
    status_tracker.update_job_status(
        job_id, ProcessingStatus.FAILED, error_msg, error_message=error_msg
    )
    broadcast_to_import_queue_module(user_id, 'status_updated', {
        'id': queue.id,
        'status': 'failed',
        'progress': 0.0,
        'message': PROCESSING_FAILED,
    })
    broadcast_to_process_status_module(user_id, queue.id, 'item_failed', {
        'job_id': job_id,
        'error_message': error_msg,
    })


def _is_shutdown(exc: Exception) -> bool:
    error_str = str(exc)
    return isinstance(exc, RuntimeError) and (
        'cannot schedule new futures after interpreter shutdown' in error_str
        or 'cannot schedule new futures after shutdown' in error_str
    )


def _user_error_message(exc: Exception) -> str:
    if not settings.PROCESSING_SHOW_DETAILED_ERROR_MESSAGES:
        return ERROR_OCCURRED_DURING_PROCESSING
    message = str(exc) if exc else "Unknown error"
    if len(message) > 200:
        message = message[:200] + "..."
    return f"{ERROR_OCCURRED_DURING_PROCESSING}: {type(exc).__name__}: {message}"
