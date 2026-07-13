"""
Process job processor for asynchronous file processing.
Handles converting uploaded files to geojson representation.
"""

import base64
import json
import re
import time
import traceback
from typing import Dict, Any, Optional

from celery.exceptions import SoftTimeLimitExceeded
from django.conf import settings
from redis.exceptions import LockError

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.job_ceiling import calculate_job_ceiling_seconds
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus
from geo_lib.processing.jobs.process_job.broadcasting import (
    broadcast_item_added,
    broadcast_to_import_queue_module,
    broadcast_to_process_status_module,
)
from geo_lib.processing.jobs.process_job.dispatch import (
    ImportLockContention,
    IMPORT_LOCK_TTL_BUFFER_SECONDS,
    dispatch_import_job,
)
from geo_lib.processing.jobs.process_job.job_control import check_cancellation, check_job_timeout
from geo_lib.processing.jobs.process_job.persistence import (
    create_initial_import_queue_entry,
    finalize_and_save_processed_features,
    mark_import_queue_as_failed,
)
from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
from geo_lib.processing.messages import (
    PROCESSING_FAILED,
    FILE_VALIDATION_FAILED,
    ERROR_OCCURRED_DURING_PROCESSING,
    PROCESSING_TIMEOUT,
)
from geo_lib.processing.processors import get_processor
from geo_lib.security.exceptions import FileValidationError, SecurityError
from geo_lib.utils.redis_locks import try_acquire_lock

_logger = get_tagged_logger('ProcessJob')


class ProcessJob(BaseJob):
    """
    Handles asynchronous file processing (converting to geojson).

    Dispatches to a Celery task (queue `imports`) rather than a thread: `process_locked` is
    the actual entry point the task calls, and enforces the same one-file-at-a-time-per-user
    serialization the old single-worker-thread-per-user design provided, via a Redis lock
    instead of an in-process queue.
    """

    def get_job_type(self) -> str:
        return "process"

    def enqueue_job(self, job_id: str, file_data: bytes, filename: str, user_id: int, replacement_feature_id: Optional[int] = None):
        """
        Persist a job's ImportQueue entry and hand it off to the `imports` Celery queue.

        Args:
            job_id: Unique job identifier
            file_data: File content as bytes
            filename: Original filename
            user_id: ID of the user who uploaded the file
            replacement_feature_id: Optional ID of the feature being updated (for replacement uploads)
        """
        # Create initial ImportQueue entry WITH raw file data so it can be recovered if server restarts
        import_queue_id = create_initial_import_queue_entry(
            filename, user_id, file_data, replacement_feature_id=replacement_feature_id
        )
        self.status_tracker.set_job_import_queue_id(job_id, import_queue_id)
        broadcast_item_added(user_id, import_queue_id)

        job_data = {
            'job_id': job_id,
            'import_queue_id': import_queue_id,
            'filename': filename,
            'user_id': user_id,
            'timestamp': time.time(),
            'replacement_feature_id': replacement_feature_id,
            'job_ceiling_seconds': calculate_job_ceiling_seconds(len(file_data)),
        }

        self.status_tracker.update_job_status(
            job_id,
            ProcessingStatus.QUEUED,
            "Waiting in queue for processing"
        )
        self._broadcast_job_status_updated(
            user_id,
            job_id,
            'queued',
            0.0,
            "Waiting in queue for processing",
            import_queue_id=import_queue_id
        )

        dispatch_import_job(job_id, job_data)

    def process_locked(self, job_id: str, job_data: Dict[str, Any]) -> None:
        """
        Process one queued job while holding an exclusive per-user lock.

        This is what makes files uploaded by the same user process one at a time: if another
        job for this user is already running, raises `ImportLockContention` instead of
        blocking, so the Celery task wrapper (`api.tasks.process_import_job`) can retry later
        rather than tying up a worker slot waiting on it.
        """
        user_id = job_data['user_id']
        lock_ttl_seconds = job_data['job_ceiling_seconds'] + IMPORT_LOCK_TTL_BUFFER_SECONDS
        lock = try_acquire_lock(f"import_processing_lock:user:{user_id}", timeout_seconds=lock_ttl_seconds)
        if lock is None:
            raise ImportLockContention(f"Another import job is already processing for user {user_id}")

        try:
            job = self.status_tracker.get_job(job_id)
            if not job or job.status == ProcessingStatus.CANCELED:
                _logger.info(f"Job {job_id} was canceled before processing started")
                return

            self.status_tracker.update_job_status(job_id, ProcessingStatus.PROCESSING, "Processing...", 0.0)
            self._execute_job(job_id, job_data)
        finally:
            try:
                lock.release()
            except LockError:
                pass  # Lock already expired; nothing to release.

    def _handle_processing_error(self, job_id: str, user_id: int, error_msg: str, realtime_log: RealTimeImportLog):
        """Handle processing errors by logging, updating status, and broadcasting events."""
        realtime_log.add(error_msg, "ProcessJob", DatabaseLogLevel.ERROR)

        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.FAILED,
            error_msg, error_message=error_msg
        )

        job = self.status_tracker.get_job(job_id)
        mark_import_queue_as_failed(job.import_queue_id, error_msg)

        # Broadcast high-level status to realtime channel (processing failed)
        broadcast_to_import_queue_module(user_id, 'status_updated', {
            'id': job.import_queue_id,
            'status': 'failed',
            'progress': 0.0,
            'message': PROCESSING_FAILED
        })

        # Broadcast detailed failure to process status channel
        broadcast_to_process_status_module(user_id, job.import_queue_id, 'item_failed', {
            'job_id': job_id,
            'error_message': error_msg
        })

        self._broadcast_job_failed(job_id, error_msg)

    def _finalize_job_success(self, job_id: str, user_id: int, import_queue_id: int,
                              feature_count: int, overall_duration: float,
                              realtime_log: RealTimeImportLog) -> None:
        """Finalize successful job completion with broadcasts and result setting."""
        realtime_log.add_timing("Total file processing", overall_duration, "ProcessJob")

        completion_msg = f"File processing completed! Processed {feature_count} features in {overall_duration:.1f}s"
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.COMPLETED,
            completion_msg, 100.0
        )

        # Broadcast high-level status to realtime channel (processing completed)
        broadcast_to_import_queue_module(user_id, 'status_updated', {
            'id': import_queue_id,
            'status': 'completed',
            'progress': 100.0,
            'message': 'Processing completed'
        })

        # Broadcast detailed completion to process status channel
        broadcast_to_process_status_module(user_id, import_queue_id, 'item_completed', {
            'job_id': job_id,
            'message': completion_msg
        })
        realtime_log.add(completion_msg, "ProcessJob", DatabaseLogLevel.INFO)

        _logger.info(f"Job {job_id} completed: {feature_count} features processed in {overall_duration:.1f}s")

        self._broadcast_job_completed(user_id, job_id)

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the process job processing logic.

        This method signature matches the abstract `BaseJob._execute_job` interface, but
        ProcessJob is only ever invoked via `process_locked` (from the Celery task), so
        `kwargs` here is actually the full `job_data` dict built by `enqueue_job`/
        `job_recovery`, not a `**kwargs`-style call.

        Args:
            job_id: Job ID (part of BaseJob interface, also in kwargs)
            kwargs: Job data containing job_id, import_queue_id, filename, user_id, etc.
        """
        job_data = kwargs
        filename = job_data['filename']
        user_id = job_data['user_id']

        # Get job and import queue info
        job = self.status_tracker.get_job(job_id)
        if not job or not job.import_queue_id:
            _logger.error(f"Process job {job_id} not found or missing import_queue_id")
            return

        import_queue_id = job.import_queue_id

        # Get the ImportQueue entry and read raw_file from database
        try:
            import_queue = ImportQueue.objects.get(id=import_queue_id)
            assert import_queue.log_id

            # Read raw_file from database and convert to bytes
            raw_file = import_queue.raw_file
            if not raw_file:
                _logger.error(f"ImportQueue {import_queue_id} has no raw_file data")
                self._handle_processing_error(
                    job_id, user_id,
                    "File data not found in database",
                    RealTimeImportLog(user_id, import_queue.log_id)
                )
                return

            # Convert raw_file string back to bytes
            # raw_file is stored as UTF-8 string for text files, or base64 string for binary files
            # Heuristic: if string looks like base64 (only base64 chars, reasonable length), try base64 first
            # Otherwise, treat as UTF-8 text
            base64_pattern = re.compile(r'^[A-Za-z0-9+/]*={0,2}$')
            is_likely_base64 = (
                len(raw_file) > 20 and  # Base64 strings are typically longer
                base64_pattern.match(raw_file.replace('\n', '').replace('\r', '')) and
                len(raw_file) % 4 == 0  # Base64 length is multiple of 4 (after padding)
            )

            if is_likely_base64:
                try:
                    # Try base64 decode for binary files (KMZ, etc.)
                    file_data = base64.b64decode(raw_file)
                except Exception:
                    # If base64 decode fails, fall back to UTF-8
                    file_data = raw_file.encode('utf-8')
            else:
                # Treat as UTF-8 text (GPX, KML)
                file_data = raw_file.encode('utf-8')

            # Check if this is a replacement upload (fast path)
            is_replacement = import_queue.replacement is not None
        except ImportQueue.DoesNotExist:
            # ImportQueue was deleted (likely by user deletion), stop processing
            return

        realtime_log = RealTimeImportLog(user_id, import_queue.log_id)
        overall_start_time = time.time()

        # Log start of processing
        file_size_mb = len(file_data) / (1024 * 1024)
        _logger.info(f"Starting upload processing for job {job_id}: file '{filename}' ({file_size_mb:.2f} MB), replacement={is_replacement}")

        try:
            # Update status to processing
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "Starting file validation and processing...", 12.0
            )

            # Broadcast high-level status to realtime channel (processing started)
            broadcast_to_import_queue_module(user_id, 'status_updated', {
                'id': import_queue_id,
                'status': 'processing',
                'progress': 12.0,
                'message': 'Processing started'
            })

            realtime_log.add(f"Processing {file_size_mb:.1f}MB file", "ProcessJob", DatabaseLogLevel.INFO)

            # Create processor instance
            # Use minimal processing for replacement uploads (skip tags, reverse_geocoding)
            # Pass realtime_log so long-running steps (e.g. elevation batches) stream to the UI
            processor = get_processor(
                file_data,
                filename,
                job_id=job_id,
                status_tracker=self.status_tracker,
                minimal_processing=is_replacement,
                user_id=user_id,
                import_queue_id=import_queue_id,
                realtime_log=realtime_log,
            )

            # Detect file type (needed for timing labels)
            file_type = processor.detect_file_type()

            # Update status for validation
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "Validating file format and security...", 24.0
            )
            realtime_log.add("Validating file format and security", "ProcessJob", DatabaseLogLevel.INFO)

            # Validate file using processor
            is_valid, validation_error = processor.validate()
            if not is_valid:
                error_msg = f"{FILE_VALIDATION_FAILED}: {validation_error}"
                realtime_log.add(error_msg, "ProcessJob", DatabaseLogLevel.ERROR)

                mark_import_queue_as_failed(import_queue_id, "File validation failed")

                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.FAILED,
                    error_msg,
                    error_message="File validation failed"
                )

                # Broadcast high-level status to realtime channel (processing failed)
                broadcast_to_import_queue_module(user_id, 'status_updated', {
                    'id': import_queue_id,
                    'status': 'failed',
                    'progress': 0.0,
                    'message': PROCESSING_FAILED
                })

                # Broadcast detailed failure to process status channel
                broadcast_to_process_status_module(user_id, import_queue_id, 'item_failed', {
                    'job_id': job_id,
                    'error_message': error_msg
                })
                return

            # Check if job was canceled after validation
            if self._check_cancellation(job_id, realtime_log, "after validation"):
                return
            self._check_job_timeout(job_id, overall_start_time, realtime_log, processor, "after validation")

            # Calculate progress percentages based on path type (fast path vs normal)
            validation_progress = 20.0 if is_replacement else 36.0
            conversion_progress = 60.0 if is_replacement else 48.0

            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "File validation passed, starting conversion...", validation_progress
            )

            # Step 2: Convert file to GeoJSON format
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "Converting file to GeoJSON format...", conversion_progress
            )
            realtime_log.add("Converting file to GeoJSON format", "ProcessJob", DatabaseLogLevel.INFO)

            conversion_start = time.time()
            step3_log = processor.step_3_convert_to_geojson()
            conversion_duration = time.time() - conversion_start
            realtime_log.extend(step3_log)
            realtime_log.add_timing(f"{file_type.value.upper()} conversion", conversion_duration, "ProcessJob")

            if not processor.geojson_data or 'features' not in processor.geojson_data:
                raise FileValidationError("Processor returned invalid GeoJSON data")

            # Check for cancellation after conversion
            if self._check_cancellation(job_id, realtime_log, "after GeoJSON conversion"):
                return
            self._check_job_timeout(job_id, overall_start_time, realtime_log, processor, "after GeoJSON conversion")

            # Calculate progress percentages for remaining steps
            # For normal path: 48 (conv) -> 60 (split) -> 72 (elev) -> 80 (tags) -> 88 (reverse geocode) -> 96 (db)
            # For replacement: 60 (conv) -> 100 (db) [skip split, elev, tags, reverse geocode]
            if is_replacement:
                db_update_progress = 100.0
            else:
                db_update_progress = 96.0
            split_progress = 60.0
            elevation_progress = 72.0
            geocoding_progress = 88.0

            # Step 3: Split and validate features
            if not is_replacement:
                self._update_and_broadcast_status(
                    job_id, user_id, import_queue_id,
                    "Splitting and validating features...", split_progress
                )
                realtime_log.add("Splitting and validating features", "ProcessJob", DatabaseLogLevel.INFO)

                split_start = time.time()
                processor.processed_features, split_log = processor.step_4_split_and_validate_features(processor.geojson_data)
                split_duration = time.time() - split_start
                realtime_log.extend(split_log)
                realtime_log.add_timing("Feature splitting and validation", split_duration, "ProcessJob")

                # Check for cancellation after splitting
                if self._check_cancellation(job_id, realtime_log, "after feature splitting"):
                    return
                self._check_job_timeout(job_id, overall_start_time, realtime_log, processor, "after feature splitting")
            else:
                # For replacement uploads, still need to split features but don't show progress
                processor.processed_features, split_log = processor.step_4_split_and_validate_features(processor.geojson_data)
                realtime_log.extend(split_log)

            # Step 4: Fill elevation data
            if not is_replacement:
                self._update_and_broadcast_status(
                    job_id, user_id, import_queue_id,
                    "Filling elevation data...", elevation_progress
                )
                realtime_log.add("Filling elevation data", "ProcessJob", DatabaseLogLevel.INFO)

                elevation_start = time.time()
                elevation_log = processor.step_5_fill_elevations()
                elevation_duration = time.time() - elevation_start
                realtime_log.extend(elevation_log)
                realtime_log.add_timing("Elevation data filling", elevation_duration, "ProcessJob")

                # Check for cancellation after elevation filling
                if self._check_cancellation(job_id, realtime_log, "after elevation filling"):
                    return
                self._check_job_timeout(job_id, overall_start_time, realtime_log, processor, "after elevation filling")
            else:
                # For replacement uploads, still fill elevations but don't show progress
                elevation_log = processor.step_5_fill_elevations()
                realtime_log.extend(elevation_log)

            # Step 5: Tagging and Reverse Geocoding (minimal_processing flag skips this)
            # Note: All tagging (including reverse geocoding) is now done in step 7
            if not is_replacement:
                self._update_and_broadcast_status(
                    job_id, user_id, import_queue_id,
                    "Tagging and Reverse Geocoding...", geocoding_progress
                )
                realtime_log.add("Tagging and Reverse Geocoding", "ProcessJob", DatabaseLogLevel.INFO)

                tagging_start = time.time()
                tagging_log = processor.step_7_tag_features()
                tagging_duration = time.time() - tagging_start
                realtime_log.extend(tagging_log)
                realtime_log.add_timing("Tagging and Reverse Geocoding", tagging_duration, "ProcessJob")

                # Check for cancellation after tagging and reverse geocoding
                if self._check_cancellation(job_id, realtime_log, "after tagging and reverse geocoding"):
                    return
                self._check_job_timeout(job_id, overall_start_time, realtime_log, processor, "after tagging and reverse geocoding")

            # Check if job was canceled before finalization
            if self._check_cancellation(job_id, realtime_log, "before feature finalization"):
                return
            self._check_job_timeout(job_id, overall_start_time, realtime_log, processor, "before feature finalization")

            # Finalize features (hashing, type summary, track override, duplicate detection)
            finalization_start = time.time()
            finalization_result, finalization_log = processor.finalize_features()
            finalization_duration = time.time() - finalization_start
            realtime_log.extend(finalization_log)
            realtime_log.add_timing("Feature finalization", finalization_duration, "ProcessJob")

            # Extract results
            geojson_data = finalization_result['geojson_data']
            duplicate_features = finalization_result['duplicate_features']
            feature_count = finalization_result['feature_count']

            # Prepare GeoJSON string and size for database storage
            geojson_str = json.dumps(geojson_data)
            geojson_size_mb = len(geojson_str) / (1024 * 1024)

            # Update progress for database operations
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "Updating database entry...", db_update_progress
            )
            realtime_log.add("Updating database entry", "ProcessJob", DatabaseLogLevel.INFO)

            # Check if job was canceled before database update
            if self._check_cancellation(job_id, realtime_log, "before database update"):
                return

            # Save processed features to database
            feature_processing_start = time.time()
            import_queue_id = finalize_and_save_processed_features(
                self.status_tracker, geojson_data, duplicate_features, realtime_log,
                user_id, job_id, geojson_size_mb, file_data, self._update_and_broadcast_status,
            )
            feature_processing_duration = time.time() - feature_processing_start
            realtime_log.add_timing("Database save", feature_processing_duration, "ProcessJob")

            # Finalize job success
            overall_duration = time.time() - overall_start_time
            _logger.info(f"Completed upload processing for job {job_id}: {feature_count} features processed in {overall_duration:.2f}s")
            self._finalize_job_success(job_id, user_id, import_queue_id, feature_count,
                                       overall_duration, realtime_log)

        except (TimeoutError, SoftTimeLimitExceeded):
            # Timeout during processing: the per-conversion timeout, the overall job ceiling
            # check (both in-process, see _check_job_timeout), or Celery's own soft_time_limit
            # (a backstop for stages with no explicit checkpoint of their own)
            overall_duration = time.time() - overall_start_time
            _logger.error(f"Upload processing timeout for job {job_id} after {overall_duration:.2f}s")
            realtime_log.add(PROCESSING_TIMEOUT, "ProcessJob", DatabaseLogLevel.ERROR)
            self._handle_processing_error(job_id, user_id, PROCESSING_TIMEOUT, realtime_log)
        except (SecurityError, FileValidationError) as e:
            # Security or validation error - use the specific error message
            overall_duration = time.time() - overall_start_time
            _logger.error(f"Upload processing failed for job {job_id} after {overall_duration:.2f}s: Security/validation error - {str(e)}")
            error_msg = str(e) if str(e) else FILE_VALIDATION_FAILED
            self._handle_processing_error(job_id, user_id, f"{FILE_VALIDATION_FAILED}: {error_msg}", realtime_log)
        except Exception as e:
            # Check if this is a shutdown-related error
            # These occur when the server is killed while a job is processing
            # The job will be recovered on restart, so don't mark it as failed
            error_str = str(e)
            is_shutdown_error = (
                isinstance(e, RuntimeError) and
                ('cannot schedule new futures after interpreter shutdown' in error_str or
                 'cannot schedule new futures after shutdown' in error_str)
            )

            if is_shutdown_error:
                _logger.info(f"Job {job_id} interrupted by server shutdown - will be recovered on restart")
                return

            overall_duration = time.time() - overall_start_time
            file_size_mb = len(file_data) / (1024 * 1024) if file_data else 0
            _logger.error(f"Upload processing error for job {job_id} after {overall_duration:.2f}s: file '{filename}' ({file_size_mb:.2f} MB): {traceback.format_exc()}")

            # Check if detailed error messages are enabled (default: True)
            show_detailed = settings.PROCESSING_SHOW_DETAILED_ERROR_MESSAGES

            if show_detailed:
                # Capture exception type and message, truncate if too long
                exception_type = type(e).__name__
                exception_message = str(e) if e else "Unknown error"
                max_message_length = 200
                if len(exception_message) > max_message_length:
                    exception_message = exception_message[:max_message_length] + "..."

                error_msg = f"{ERROR_OCCURRED_DURING_PROCESSING}: {exception_type}: {exception_message}"
            else:
                # Use generic error message
                error_msg = ERROR_OCCURRED_DURING_PROCESSING

            self._handle_processing_error(job_id, user_id, error_msg, realtime_log)

    def _check_cancellation(self, job_id: str, processing_log: RealTimeImportLog, stage: str) -> bool:
        return check_cancellation(self.status_tracker, job_id, processing_log, stage)

    def _check_job_timeout(self, job_id: str, overall_start_time: float,
                           processing_log: RealTimeImportLog, processor, stage: str) -> None:
        check_job_timeout(job_id, overall_start_time, processing_log, processor, stage)

    def _update_and_broadcast_status(self, job_id: str, user_id: int,
                                     import_queue_id: int, message: str, progress: float):
        """
        Update job status in tracker and broadcast via WebSocket.

        Args:
            job_id: The job ID to update
            user_id: User ID for WebSocket broadcast
            import_queue_id: Import queue ID for WebSocket broadcast
            message: Status message
            progress: Progress percentage (0-100)
        """
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            message, progress
        )

        broadcast_to_process_status_module(user_id, import_queue_id, 'status_updated', {
            'status': 'processing',
            'progress': progress,
            'message': message
        })
