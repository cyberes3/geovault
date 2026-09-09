import time
from enum import Enum

from geo_lib.importing.errors import ImportCancelled, ImportTimeout
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, ProcessingStatusTracker
from geo_lib.processing.jobs.process_job.broadcasting import broadcast_to_process_status_module


class JobPhase(str, Enum):
    PROCESS = 'process'
    APPLY = 'apply'
    DELETE = 'delete'
    RECHECK = 'recheck'


class JobRuntime:
    def __init__(
        self,
        status_tracker: ProcessingStatusTracker,
        job_id: str,
        user_id: int,
        queue_id: int | None,
        phase: JobPhase,
        started_at: float,
        ceiling_seconds: int,
    ):
        self.status_tracker = status_tracker
        self.job_id = job_id
        self.user_id = user_id
        self.queue_id = queue_id
        self.phase = phase
        self.started_at = started_at
        self.ceiling_seconds = ceiling_seconds

    def check_cancelled(self) -> bool:
        job = self.status_tracker.get_job(self.job_id)
        if job is None:
            return False
        return job.status == ProcessingStatus.CANCELED

    def check_timeout(self) -> None:
        elapsed = time.time() - self.started_at
        if elapsed > self.ceiling_seconds:
            raise ImportTimeout('after elapsed ceiling', self.ceiling_seconds)

    def checkpoint(self, stage: str) -> None:
        if self.check_cancelled():
            raise ImportCancelled(stage)
        self.check_timeout()

    def report_progress(self, message: str, progress: float) -> None:
        self.status_tracker.update_job_status(
            self.job_id, ProcessingStatus.PROCESSING, message, progress
        )
        if self.queue_id is not None and self.phase == JobPhase.PROCESS:
            broadcast_to_process_status_module(self.user_id, self.queue_id, 'status_updated', {
                'status': 'processing',
                'progress': progress,
                'message': message,
            })
