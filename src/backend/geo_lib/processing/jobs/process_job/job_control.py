"""
Cancellation and timeout checks used throughout the process job pipeline.

Both checks are called repeatedly between pipeline steps in `ProcessJob._execute_job`, so
they're kept as small, dependency-light functions rather than methods, to make each call
site's intent obvious without needing to trace back into a large class body.
"""

import time

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, ProcessingStatusTracker
from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
from geo_lib.processing.processors import BaseProcessor

_logger = get_tagged_logger('ProcessJob')


def check_cancellation(status_tracker: ProcessingStatusTracker, job_id: str,
                        processing_log: RealTimeImportLog, stage: str) -> bool:
    """
    Check if a job was canceled.

    Args:
        status_tracker: Tracker to read the job's current status from
        job_id: The job ID to check
        processing_log: Log to add a cancellation message to
        stage: Description of the processing stage, for logging

    Returns:
        True if the job was canceled, False otherwise
    """
    job = status_tracker.get_job(job_id)
    if job.status == ProcessingStatus.CANCELED:
        _logger.info(f"Job {job_id} was canceled {stage}")
        processing_log.add(f"Processing canceled {stage}", "ProcessJob", DatabaseLogLevel.WARNING)
        return True
    return False


def check_job_timeout(job_id: str, overall_start_time: float, processing_log: RealTimeImportLog,
                       processor: BaseProcessor, stage: str) -> None:
    """
    Defense-in-depth overall job ceiling, independent of the per-conversion timeout in
    `BaseProcessor._convert_to_geojson`. That timeout only bounds one pipeline step; this
    bounds the whole job's wall-clock time so it can never occupy a user's queue worker
    indefinitely, even if some other stage (splitting, elevation, tagging, DB write)
    regresses without its own bound.

    Raises TimeoutError (caught by the same top-level handler as the conversion timeout)
    if the job has run longer than its size-scaled ceiling.
    """
    elapsed = time.time() - overall_start_time
    ceiling_seconds = processor.calculate_job_ceiling_seconds()
    if elapsed > ceiling_seconds:
        _logger.error(f"Job {job_id} exceeded overall processing ceiling of {ceiling_seconds}s (elapsed {elapsed:.1f}s) {stage}")
        processing_log.add(f"Processing exceeded overall time ceiling {stage}", "ProcessJob", DatabaseLogLevel.ERROR)
        raise TimeoutError(f"Job exceeded overall processing ceiling of {ceiling_seconds}s")
