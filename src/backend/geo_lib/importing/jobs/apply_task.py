import time
import traceback

from api.models import ImportQueue
from geo_lib.importing.apply_plan import ApplyPlan
from geo_lib.importing.errors import ImportBatchWriteError, ImportCancelled, ImportStageError
from geo_lib.importing.pipeline import ImportPipeline
from geo_lib.importing.runtime import JobPhase, JobRuntime
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, ProcessingStatusTracker
from geo_lib.processing.jobs.process_job.broadcasting import broadcast_to_process_status_module

_logger = get_tagged_logger('ApplyTask')


def run_apply_job(
    status_tracker: ProcessingStatusTracker,
    job_id: str,
    item_id: int,
    user_id: int,
    import_custom_icons: bool,
    extra_user_skipped: list[str] | None = None,
    extra_user_restored: list[str] | None = None,
    ceiling_seconds: int = 600,
) -> None:
    runtime = JobRuntime(
        status_tracker=status_tracker,
        job_id=job_id,
        user_id=user_id,
        queue_id=item_id,
        phase=JobPhase.APPLY,
        started_at=time.time(),
        ceiling_seconds=ceiling_seconds,
    )
    if runtime.check_cancelled():
        status_tracker.update_job_status(job_id, ProcessingStatus.CANCELED, "Import canceled")
        return
    try:
        queue = ImportQueue.objects.get(id=item_id, user_id=user_id)
    except ImportQueue.DoesNotExist:
        status_tracker.update_job_status(
            job_id, ProcessingStatus.FAILED, f"Import queue item {item_id} not found",
            error_message=f"Import queue item {item_id} not found",
        )
        return
    plan = ApplyPlan.from_queue(
        queue,
        import_custom_icons=import_custom_icons,
        extra_user_skipped=extra_user_skipped,
        extra_user_restored=extra_user_restored,
    )
    status_tracker.update_job_status(job_id, ProcessingStatus.PROCESSING, "Importing features...", 30.0)
    try:
        result = ImportPipeline().apply(plan, runtime)
    except ImportCancelled:
        status_tracker.update_job_status(job_id, ProcessingStatus.CANCELED, "Import canceled")
        return
    except (ImportStageError, ImportBatchWriteError) as exc:
        message = exc.message if hasattr(exc, 'message') else str(exc)
        status_tracker.update_job_status(job_id, ProcessingStatus.FAILED, message, error_message=message)
        broadcast_to_process_status_module(user_id, item_id, 'item_failed', {
            'message': f'Import failed: {message}',
            'error': message,
        })
        return
    except Exception:
        _logger.error(f"Apply job {job_id} failed: {traceback.format_exc()}")
        status_tracker.update_job_status(
            job_id, ProcessingStatus.FAILED, "Import failed", error_message="Import failed"
        )
        broadcast_to_process_status_module(user_id, item_id, 'item_failed', {
            'message': 'Import failed',
            'error': 'Import failed',
        })
        return
    imported = result['imported']
    skipped = result.get('duplicates_skipped') or {'hash': [], 'geometry': []}
    message = f"Successfully imported {imported} features"
    status_tracker.update_job_status(job_id, ProcessingStatus.COMPLETED, message, 100.0)
    broadcast_to_process_status_module(user_id, item_id, 'item_completed', {
        'message': message,
        'imported_count': imported,
        'skipped_count': 0,
        'duplicates_skipped': {
            'hash': skipped.get('hash') or [],
            'geometry': skipped.get('geometry') or [],
        },
    })
