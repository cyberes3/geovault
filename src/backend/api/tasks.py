import time
from typing import Any, Dict

from celery import shared_task

from api.services.replacement_cleanup_service import cleanup_orphaned_replacements
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.jobs.process_job.dispatch import (
    ImportLockContention,
    IMPORT_CELERY_QUEUE_NAME,
    IMPORT_CELERY_TASK_NAME,
)
from geo_lib.processing.jobs.process_job.job import ProcessJob
from geo_lib.utils.redis_connection import get_redis_connection

CELERY_BEAT_HEARTBEAT_KEY = "celery_beat_heartbeat"

# How long to wait before a job retries after losing the per-user processing lock to another
# job. Short, since contention is routine (not an error) and resolves as soon as the job
# currently holding the lock finishes.
IMPORT_LOCK_RETRY_COUNTDOWN_SECONDS = 5


@shared_task(name="api.celery_health.ping_worker", queue="maintenance")
def ping_worker() -> str:
    """Lightweight task used by startup checks to verify workers can execute jobs."""
    return "pong"


@shared_task(name="api.celery_health.beat_heartbeat", queue="maintenance")
def beat_heartbeat() -> float:
    """Periodic heartbeat task used to verify celery-beat scheduling."""
    now = time.time()
    redis_client = get_redis_connection()
    redis_client.set(CELERY_BEAT_HEARTBEAT_KEY, str(now))
    return now


@shared_task(name=IMPORT_CELERY_TASK_NAME, queue=IMPORT_CELERY_QUEUE_NAME, bind=True, max_retries=None)
def process_import_job(self, job_id: str, job_data: Dict[str, Any]) -> None:
    """
    Process one queued file-import job.

    `time_limit`/`soft_time_limit` are set per-dispatch (see `dispatch_import_job`), scaled to
    the specific file's size, so they aren't repeated as static decorator options here.

    Not called directly: dispatched by name from
    `geo_lib.processing.jobs.process_job.dispatch.dispatch_import_job`, which builds `job_data`.
    """
    try:
        ProcessJob(status_tracker).process_locked(job_id, job_data)
    except ImportLockContention:
        raise self.retry(countdown=IMPORT_LOCK_RETRY_COUNTDOWN_SECONDS, max_retries=None)
