import time

from api.models import ImportQueue
from geo_lib.duplicates.adapters.import_draft import build_duplicate_index
from geo_lib.duplicates.detector import DuplicateDetector
from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.importing.draft_store import ImportDraftStore, serialize_page
from geo_lib.importing.errors import ImportCancelled
from geo_lib.importing.runtime import JobPhase, JobRuntime
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, ProcessingStatusTracker
from geo_lib.processing.jobs.process_job.broadcasting import broadcast_to_process_status_module
from geo_lib.processing.logging import DatabaseLogLevel, RealTimeImportLog

_logger = get_tagged_logger('RecheckTask')


def run_recheck_job(
    status_tracker: ProcessingStatusTracker,
    job_id: str,
    item_id: int,
    user_id: int,
    current_page: int = 1,
    ceiling_seconds: int = 600,
) -> None:
    runtime = JobRuntime(
        status_tracker=status_tracker,
        job_id=job_id,
        user_id=user_id,
        queue_id=item_id,
        phase=JobPhase.RECHECK,
        started_at=time.time(),
        ceiling_seconds=ceiling_seconds,
    )
    if runtime.check_cancelled():
        status_tracker.update_job_status(job_id, ProcessingStatus.CANCELED, "Recheck canceled")
        return
    queue = ImportQueue.objects.get(id=item_id, user_id=user_id)
    store = ImportDraftStore(queue)
    drafts = list(queue.draft_features.order_by('spatial_index', 'id'))
    features = [draft.geojson for draft in drafts]
    realtime_log = RealTimeImportLog(user_id, queue.log_id) if queue.log_id else None
    if realtime_log:
        realtime_log.add("Starting duplicate re-check", "Duplicate Recheck", DatabaseLogLevel.INFO)
    try:
        runtime.checkpoint('before recheck detect')
        detector = DuplicateDetector()
        unique, dropped = detector.dedupe_internal(features)
        index = build_duplicate_index(user_id, unique, queue.id, queue.timestamp)
        verdicts = detector.detect_two_pass(unique, index)
        prior = SkipIntent.from_stored(queue.skip_intent)
        skip_intent = SkipIntent.from_verdicts(verdicts, prior)
        store.rewrite_verdicts(verdicts, skip_intent)
        runtime.checkpoint('after recheck persist')
    except ImportCancelled:
        status_tracker.update_job_status(job_id, ProcessingStatus.CANCELED, "Recheck canceled")
        return
    page = store.page(current_page, 50)
    broadcast_to_process_status_module(user_id, item_id, 'page', serialize_page(page))
    hash_count = queue.duplicate_counts.get('hash', 0) if queue.duplicate_counts else 0
    geometry_count = queue.duplicate_counts.get('geometry', 0) if queue.duplicate_counts else 0
    if realtime_log:
        realtime_log.add(
            f"Duplicate re-check completed. Found {hash_count + geometry_count} duplicate(s)",
            "Duplicate Recheck",
            DatabaseLogLevel.INFO,
        )
    status_tracker.update_job_status(
        job_id, ProcessingStatus.COMPLETED,
        f"Recheck complete ({hash_count} hash, {geometry_count} geometry)",
        100.0,
    )
