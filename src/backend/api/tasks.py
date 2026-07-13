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

# These two health-check tasks only touch Redis/return a constant, so a hang almost certainly
# means the broker/worker itself is wedged - fail fast rather than tie up the maintenance queue.
HEALTH_CHECK_SOFT_TIME_LIMIT_SECONDS = 10
HEALTH_CHECK_TIME_LIMIT_SECONDS = 20

# Cleanup deletes a handful of stale rows by primary key - generous headroom over the expected
# sub-second runtime in case of a large backlog or a slow DB.
REPLACEMENT_CLEANUP_SOFT_TIME_LIMIT_SECONDS = 120
REPLACEMENT_CLEANUP_TIME_LIMIT_SECONDS = 150


@shared_task(
    name="api.celery_health.ping_worker",
    queue="maintenance",
    soft_time_limit=HEALTH_CHECK_SOFT_TIME_LIMIT_SECONDS,
    time_limit=HEALTH_CHECK_TIME_LIMIT_SECONDS,
    acks_late=True,
)
def ping_worker() -> str:
    """Lightweight task used by startup checks to verify workers can execute jobs."""
    return "pong"


@shared_task(
    name="api.celery_health.beat_heartbeat",
    queue="maintenance",
    soft_time_limit=HEALTH_CHECK_SOFT_TIME_LIMIT_SECONDS,
    time_limit=HEALTH_CHECK_TIME_LIMIT_SECONDS,
    acks_late=True,
)
def beat_heartbeat() -> float:
    """Periodic heartbeat task used to verify celery-beat scheduling."""
    now = time.time()
    redis_client = get_redis_connection()
    redis_client.set(CELERY_BEAT_HEARTBEAT_KEY, str(now))
    return now


@shared_task(
    name="api.replacement_cleanup.cleanup_orphaned_replacements",
    queue="maintenance",
    soft_time_limit=REPLACEMENT_CLEANUP_SOFT_TIME_LIMIT_SECONDS,
    time_limit=REPLACEMENT_CLEANUP_TIME_LIMIT_SECONDS,
    acks_late=True,
)
def cleanup_orphaned_replacements_task() -> int:
    """
    Celery registration for `replacement_cleanup_service.cleanup_orphaned_replacements`.

    Kept as a thin wrapper (registration/scheduling concern here, cleanup query logic in the
    service module) rather than decorating the service function directly, matching
    `process_import_job` below: Celery task definitions live in `api.tasks` where
    `celery_app.autodiscover_tasks()` is guaranteed to find them, business logic stays in its
    own module. This also makes the import of `cleanup_orphaned_replacements` a real, checked
    usage instead of a side-effect-only import whose removal would silently drop the task.
    """
    return cleanup_orphaned_replacements()


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
