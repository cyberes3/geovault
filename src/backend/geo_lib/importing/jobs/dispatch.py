from typing import Any

from website.celery_app import celery_app
from geo_lib.processing.jobs.process_job.dispatch import (
    IMPORT_CELERY_QUEUE_NAME,
    IMPORT_CELERY_TIME_LIMIT_BUFFER_SECONDS,
)

APPLY_CELERY_TASK_NAME = "api.import_processing.apply_import_job"
DELETE_CELERY_TASK_NAME = "api.import_processing.delete_import_job"
BULK_APPLY_CELERY_TASK_NAME = "api.import_processing.bulk_apply_import_job"
BULK_DELETE_CELERY_TASK_NAME = "api.import_processing.bulk_delete_import_job"
RECHECK_CELERY_TASK_NAME = "api.import_processing.recheck_duplicates_job"


def dispatch_named_job(task_name: str, args: list[Any], ceiling_seconds: int) -> None:
    celery_app.tasks[task_name].apply_async(
        args=args,
        queue=IMPORT_CELERY_QUEUE_NAME,
        soft_time_limit=ceiling_seconds,
        time_limit=ceiling_seconds + IMPORT_CELERY_TIME_LIMIT_BUFFER_SECONDS,
    )
