"""
Import job processor for asynchronous import operations.
"""

from typing import Any, Dict, List

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from api.models import ImportQueue
from geo_lib.importing.jobs.apply_task import run_apply_job
from geo_lib.importing.jobs.dispatch import APPLY_CELERY_TASK_NAME, dispatch_named_job
from geo_lib.importing.session import RawFile
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.job_ceiling import calculate_job_ceiling_seconds
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.jobs.helpers.status_tracker import JobType

_logger = get_tagged_logger('ImportJob')


class ImportJob(BaseJob):
    def get_job_type(self) -> str:
        return "import"

    def _broadcast_to_process_status_module(self, user_id: int, import_queue_id: int, event_type: str, data: dict):
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"process_status_{user_id}_{import_queue_id}",
                {'type': event_type, 'data': data},
            )

    def _handle_job_error(self, job_id: str, error_message: str):
        super()._handle_job_error(job_id, error_message)
        job = self.status_tracker.get_job(job_id)
        if job and job.import_queue_id:
            self._broadcast_to_process_status_module(
                job.user_id, job.import_queue_id, 'item_failed',
                {'message': f'Import failed: {error_message}', 'error': error_message},
            )

    def start_import_job(
        self,
        item_id: int,
        user_id: int,
        import_custom_icons: bool = True,
        skipped: List[str] = None,
        restored: List[str] = None,
    ) -> str:
        if skipped is None:
            skipped = []
        if restored is None:
            restored = []
        import_item = ImportQueue.objects.get(id=item_id)
        job_id = self.status_tracker.create_job(
            f"Import {import_item.original_filename}", user_id, JobType.IMPORT
        )
        self.status_tracker.set_job_import_queue_id(job_id, item_id)
        raw = RawFile.load(import_item.raw_file, import_item.raw_file_encoding, import_item.file_hash)
        ceiling_seconds = calculate_job_ceiling_seconds(len(raw.data))
        dispatch_named_job(
            APPLY_CELERY_TASK_NAME,
            [job_id, item_id, user_id, import_custom_icons, skipped, restored, ceiling_seconds],
            ceiling_seconds,
        )
        return job_id

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        run_apply_job(
            self.status_tracker,
            job_id,
            kwargs['item_id'],
            kwargs['user_id'],
            kwargs.get('import_custom_icons', True),
            kwargs.get('skipped') or [],
            kwargs.get('restored') or [],
            kwargs.get('ceiling_seconds', 600),
        )
