"""
Celery hand-off for queued process jobs.

`ProcessJob.enqueue_job` calls `dispatch_import_job` once a job's `ImportQueue` row exists
and its status has been set to queued; this module owns everything about *how* that
hand-off to Celery happens, decoupled from job-processing logic itself.
"""

from typing import Any, Dict

from website.celery_app import celery_app

# Name of the Celery task (defined in `api.tasks`) that runs a queued job's `_execute_job`.
# Looked up by name rather than importing the task directly: the task lives in `api.tasks`
# (a proper Django app, so Celery can autodiscover it), and `geo_lib` must not import from
# `api`/`website` app code.
IMPORT_CELERY_TASK_NAME = "api.import_processing.process_import_job"
IMPORT_CELERY_QUEUE_NAME = "imports"

# Celery's hard time_limit SIGKILLs the worker process shortly after soft_time_limit raises
# SoftTimeLimitExceeded inside it; this buffer is how long that in-process handling gets to run.
IMPORT_CELERY_TIME_LIMIT_BUFFER_SECONDS = 30

# The per-user lock must outlive the Celery task's own hard time_limit, so a slow job can never
# have its lock expire (and let a second job for the same user start) before Celery kills it.
IMPORT_LOCK_TTL_BUFFER_SECONDS = 60


class ImportLockContention(Exception):
    """Raised when another import job for this user already holds the per-user processing lock."""


def dispatch_import_job(job_id: str, job_data: Dict[str, Any]) -> None:
    """
    Hand a queued job off to the `imports` Celery queue.

    Using `apply_async` (rather than `Celery.send_task`, which explicitly ignores
    `task_always_eager`) means this still runs synchronously in tests/local dev when eager
    mode is enabled.

    `time_limit`/`soft_time_limit` are set per-dispatch (rather than as static task defaults)
    since they're scaled to this specific file's size via `job_data['job_ceiling_seconds']`.
    """
    job_ceiling_seconds = job_data['job_ceiling_seconds']
    celery_app.tasks[IMPORT_CELERY_TASK_NAME].apply_async(
        args=[job_id, job_data],
        queue=IMPORT_CELERY_QUEUE_NAME,
        soft_time_limit=job_ceiling_seconds,
        time_limit=job_ceiling_seconds + IMPORT_CELERY_TIME_LIMIT_BUFFER_SECONDS,
    )
