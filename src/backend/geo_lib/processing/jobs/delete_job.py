"""
Delete job processor for asynchronous item deletion.
"""

from typing import Any, Dict

from geo_lib.importing.jobs.delete_task import run_delete_job
from geo_lib.importing.jobs.dispatch import DELETE_CELERY_TASK_NAME, dispatch_named_job
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.jobs.helpers.status_tracker import JobType

_logger = get_tagged_logger('DeleteJob')


class DeleteJob(BaseJob):
    def get_job_type(self) -> str:
        return "delete"

    def start_delete_job(self, item_id: int, user_id: int, filename: str) -> str | None:
        job_id = self.status_tracker.create_job(filename, user_id, JobType.DELETE)
        self.status_tracker.set_job_import_queue_id(job_id, item_id)
        dispatch_named_job(DELETE_CELERY_TASK_NAME, [job_id, item_id, user_id, filename, 120], 120)
        return job_id

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        run_delete_job(
            self.status_tracker,
            job_id,
            kwargs['item_id'],
            kwargs['user_id'],
            kwargs['filename'],
            kwargs.get('ceiling_seconds', 120),
        )
