import time
import traceback

from api.models import ImportQueue
from geo_lib.importing.apply_plan import ApplyPlan
from geo_lib.importing.errors import ImportBatchWriteError, ImportCancelled, ImportStageError
from geo_lib.importing.pipeline import ImportPipeline
from geo_lib.importing.runtime import JobPhase, JobRuntime
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, ProcessingStatusTracker
from geo_lib.processing.messages import ITEM_IMPORT_FAILED

_logger = get_tagged_logger('BulkApplyTask')


def run_bulk_apply_job(
    status_tracker: ProcessingStatusTracker,
    job_id: str,
    item_ids: list[int],
    user_id: int,
    import_custom_icons: bool,
    ceiling_seconds: int = 3600,
) -> None:
    runtime = JobRuntime(
        status_tracker=status_tracker,
        job_id=job_id,
        user_id=user_id,
        queue_id=None,
        phase=JobPhase.APPLY,
        started_at=time.time(),
        ceiling_seconds=ceiling_seconds,
    )
    if runtime.check_cancelled():
        status_tracker.update_job_status(job_id, ProcessingStatus.CANCELED, "Bulk import canceled")
        return
    items = list(ImportQueue.objects.filter(id__in=item_ids, user_id=user_id))
    found_ids = [item.id for item in items]
    missing = set(item_ids) - set(found_ids)
    if missing:
        message = f"Items not found or not authorized: {list(missing)}"
        status_tracker.update_job_status(job_id, ProcessingStatus.FAILED, message, error_message=message)
        return
    successful = 0
    failed_imports = []
    skipped_hash: list = []
    skipped_geometry: list = []
    pipeline = ImportPipeline()
    for index, item in enumerate(items):
        if runtime.check_cancelled():
            status_tracker.update_job_status(job_id, ProcessingStatus.CANCELED, "Bulk import canceled")
            return
        progress = (index / max(len(items), 1)) * 100.0
        status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            f"Importing item {index + 1}/{len(items)}: {item.original_filename}...",
            progress,
        )
        try:
            plan = ApplyPlan.from_queue(item, import_custom_icons=import_custom_icons)
            result = pipeline.apply(plan, runtime)
            successful += 1
            skipped = result.get('duplicates_skipped') or {}
            skipped_hash.extend(skipped.get('hash') or [])
            skipped_geometry.extend(skipped.get('geometry') or [])
        except ImportCancelled:
            status_tracker.update_job_status(job_id, ProcessingStatus.CANCELED, "Bulk import canceled")
            return
        except (ImportStageError, ImportBatchWriteError) as exc:
            failed_imports.append({
                'item_id': item.id,
                'filename': item.original_filename,
                'error': exc.message,
            })
        except Exception:
            _logger.error(f"Bulk apply item {item.id}: {traceback.format_exc()}")
            failed_imports.append({
                'item_id': item.id,
                'filename': item.original_filename,
                'error': ITEM_IMPORT_FAILED,
            })
    if failed_imports:
        message = f"Completed: {successful} imported, {len(failed_imports)} failed"
    else:
        message = f"Successfully imported {successful} item(s)"
    status_tracker.update_job_status(job_id, ProcessingStatus.COMPLETED, message, 100.0)
    _logger.info(f"Bulk apply {job_id}: {message}")
