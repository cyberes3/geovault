"""
Bulk import job processor for asynchronous bulk import operations.
"""

from typing import Any, Dict, List

from geo_lib.importing.jobs.bulk_apply_task import run_bulk_apply_job
from geo_lib.importing.jobs.dispatch import BULK_APPLY_CELERY_TASK_NAME, dispatch_named_job
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.jobs.helpers.status_tracker import JobType

_logger = get_tagged_logger('BulkImportJob')


class BulkImportJob(BaseJob):
    def get_job_type(self) -> str:
        return "bulk_import"

    def start_bulk_import_job(self, item_ids: List[int], user_id: int, import_custom_icons: bool = True) -> str | None:
        filename = f"Bulk import of {len(item_ids)} item(s)"
        job_id = self.status_tracker.create_job(filename, user_id, JobType.BULK_IMPORT)
        dispatch_named_job(
            BULK_APPLY_CELERY_TASK_NAME,
            [job_id, item_ids, user_id, import_custom_icons, 3600],
            3600,
        )
        return job_id

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        run_bulk_apply_job(
            self.status_tracker,
            job_id,
            kwargs['item_ids'],
            kwargs['user_id'],
            kwargs.get('import_custom_icons', True),
            kwargs.get('ceiling_seconds', 3600),
        )
