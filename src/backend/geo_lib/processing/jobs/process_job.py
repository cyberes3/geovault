"""
Process job processor for asynchronous file processing.
Handles converting uploaded files to geojson representation.
"""

import base64
import hashlib
import json
import os
import subprocess
import time
import traceback
from typing import Dict, Any, Optional

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from django.contrib.auth.models import User
from django.core.files.uploadedfile import SimpleUploadedFile
from django.db import transaction

from api.models import ImportQueue, UserSettings, FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_job_logger
from geo_lib.processing.duplicate_detection import (
    find_duplicates_for_source,
    strip_duplicate_features
)
from geo_lib.processing.duplicate_models import (
    DuplicateMatchType,
    DuplicateSource,
    split_duplicates_by_match_type
)
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
from geo_lib.processing.queue_worker import start_worker_for_user
from geo_lib.processing.redis_queue import get_processing_queue
from geo_lib.processing.messages import (
    PROCESSING_FAILED,
    FILE_VALIDATION_FAILED,
    ERROR_OCCURRED_DURING_PROCESSING,
    PROCESSING_TIMEOUT,
    ERROR_TYPE_PROCESSING_FAILED
)
from geo_lib.processing.processors import get_processor
from geo_lib.processing.status_tracker import ProcessingStatus
from geo_lib.processing.utils import (
    encode_raw_file_data,
    inject_feature_hashes,
    build_skipped_feature_ids
)
from geo_lib.security.file_validation import SecureFileValidator
from geo_lib.security.exceptions import FileValidationError, SecurityError
from geo_lib.utils.pydantic_serialization import convert_features_to_pydantic
from geo_lib.utils.advisory_locks import advisory_lock
from geo_lib.utils.feature_utils import build_feature_type_summary

logger = get_job_logger()


class ProcessJob(BaseJob):
    """
    Handles asynchronous file processing (converting to geojson).
    Uses Redis queue for sequential processing per user.
    """

    def get_job_type(self) -> str:
        return "process"

    def enqueue_job(self, job_id: str, file_data: bytes, filename: str, user_id: int, replacement_feature_id: Optional[int] = None) -> bool:
        """
        Enqueue a file processing job to the Redis queue.
        
        Args:
            job_id: Unique job identifier
            file_data: File content as bytes
            filename: Original filename
            user_id: ID of the user who uploaded the file
            replacement_feature_id: Optional ID of the feature being updated (for replacement uploads)
            
        Returns:
            True if enqueued successfully, False otherwise
        """
        # Create initial ImportQueue entry so it shows up in the UI
        try:
            import_queue_id = self._create_initial_import_queue_entry(filename, user_id, job_id, replacement_feature_id=replacement_feature_id)
            self.status_tracker.set_job_result(job_id, {}, import_queue_id)

            # Broadcast WebSocket event for new item
            self._broadcast_item_added(user_id, import_queue_id)
        except Exception as e:
            logger.error(f"Failed to create initial import queue entry for job {job_id}: {str(e)}")
            return False

        # Enqueue job to Redis
        try:
            queue = get_processing_queue(user_id)
            job_data = {
                'job_id': job_id,
                'import_queue_id': import_queue_id,
                'filename': filename,
                'user_id': user_id,
                'file_data': file_data,
                'timestamp': time.time(),
                'replacement_feature_id': replacement_feature_id
            }
            
            success = queue.enqueue(job_data)
            if not success:
                logger.error(f"Failed to enqueue job {job_id} for user {user_id}")
                return False
            
            # Update job status to WAITING (will be set to PROCESSING when worker picks it up)
            self.status_tracker.update_job_status(
                job_id, 
                ProcessingStatus.WAITING,
                "Waiting in queue for processing"
            )
            
            # Broadcast status update to frontend
            self._broadcast_job_status_updated(
                user_id, 
                job_id, 
                'waiting', 
                0.0, 
                "Waiting in queue for processing",
                import_queue_id=import_queue_id
            )
            
            # Start worker for this user if not already running
            start_worker_for_user(user_id, self)
            
            return True
            
        except Exception as e:
            logger.error(f"Failed to enqueue job {job_id}: {e}")
            return False
    
    def _mark_import_queue_as_failed(self, import_queue_id: int, error_message: str):
        """
        Mark an ImportQueue item as unparsable and save error information.
        """
        try:
            import_queue = ImportQueue.objects.get(id=import_queue_id)
            import_queue.unparsable = True
            # Set geofeatures to indicate processing failure
            import_queue.geofeatures = [{
                'error': ERROR_TYPE_PROCESSING_FAILED,
                'message': error_message
            }]
            import_queue.save()
        except ImportQueue.DoesNotExist:
            logger.warning(f"ImportQueue {import_queue_id} not found when marking as failed")
        except Exception as e:
            logger.error(f"Failed to mark ImportQueue {import_queue_id} as unparsable: {str(e)}")

    def _handle_processing_error(self, job_id: str, user_id: int, error_msg: str, detailed_error: str, realtime_log: RealTimeImportLog):
        """
        Handle processing errors by logging, updating status, and broadcasting events.
        """
        realtime_log.add(error_msg, "ProcessJob", DatabaseLogLevel.ERROR)

        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.FAILED,
            error_msg, error_message=detailed_error
        )

        # Mark ImportQueue item as unparsable
        job = self.status_tracker.get_job(job_id)
        if job and job.import_queue_id:
            self._mark_import_queue_as_failed(job.import_queue_id, detailed_error)

            # Broadcast high-level status to realtime channel (processing failed)
            self._broadcast_to_import_queue_module(user_id, 'status_updated', {
                'id': job.import_queue_id,
                'status': 'failed',
                'progress': 0.0,
                'message': PROCESSING_FAILED
            })

            # Broadcast detailed failure to process status channel
            self._broadcast_to_process_status_module(user_id, job.import_queue_id, 'item_failed', {
                'job_id': job_id,
                'error_message': error_msg
            })

    def _apply_track_name_override(self, geojson_data: Dict[str, Any], 
                                   user_id: int, filename: str, job_id: str) -> None:
        """
        Apply user setting to overwrite single track name with filename if enabled.
        
        Args:
            geojson_data: GeoJSON data to potentially modify (modified in-place)
            user_id: User ID to check settings for
            filename: Original filename
            job_id: Job ID for logging
        """
        try:
            user_settings_obj = UserSettings.objects.filter(user_id=user_id).first()
            if user_settings_obj and user_settings_obj.settings:
                import_settings = user_settings_obj.settings.get('import', {})
                overwrite_enabled = import_settings.get('overwrite_single_track_name_with_filename', False)

                if overwrite_enabled:
                    features = geojson_data.get('features', [])
                    # Check if there's exactly one feature
                    if len(features) == 1:
                        feature = features[0]
                        geometry = feature.get('geometry', {})
                        geometry_type = geometry.get('type', '').lower() if geometry else ''
                        properties = feature.get('properties', {})

                        # Check if it's a track (LineString or MultiLineString)
                        is_track_geometry = geometry_type in ['linestring', 'multilinestring']

                        # Check if it has the type:track tag
                        system_tags = properties.get('system_tags', [])
                        is_track_tagged = 'type:track' in system_tags if isinstance(system_tags, list) else False

                        if is_track_geometry and is_track_tagged:
                            # Extract filename without extension
                            filename_without_ext = os.path.splitext(filename)[0]
                            # Overwrite the name property
                            properties['name'] = filename_without_ext
                            feature['properties'] = properties
                            logger.info(f"Overwrote single track name with filename '{filename_without_ext}' for job {job_id}")
        except Exception as e:
            # Log error but don't fail the job if setting check fails
            logger.warning(f"Error checking/applying overwrite_single_track_name_with_filename setting for job {job_id}: {str(e)}")

    def _validate_uploaded_file(self, file_data: bytes, filename: str, 
                                job_id: str, user_id: int, import_queue_id: int,
                                realtime_log: RealTimeImportLog) -> bool:
        """
        Validate uploaded file format and security.
        
        Args:
            file_data: Raw file data
            filename: Original filename
            job_id: Job ID
            user_id: User ID for broadcasting
            import_queue_id: Import queue ID for broadcasting
            realtime_log: Real-time log for messages
            
        Returns:
            True if validation passed, False otherwise
        """
        # Update status
        self._update_and_broadcast_status(
            job_id, user_id, import_queue_id,
            "Validating file format and security...", 24.0
        )
        realtime_log.add("Validating file format and security", "ProcessJob", DatabaseLogLevel.INFO)

        # Create a mock uploaded file for validation
        uploaded_file = SimpleUploadedFile(
            name=filename,
            content=file_data,
            content_type='application/zip' if filename.lower().endswith('.kmz') else 'text/xml'
        )

        # Validate file with timing
        validator = SecureFileValidator()
        validation_start = time.time()
        is_valid, validation_message = validator.validate_file(uploaded_file)
        validation_duration = time.time() - validation_start
        realtime_log.add_timing("File validation", validation_duration, "ProcessJob")

        if not is_valid:
            error_msg = f"{FILE_VALIDATION_FAILED}: {validation_message}"
            realtime_log.add(error_msg, "ProcessJob", DatabaseLogLevel.ERROR)

            # Mark ImportQueue item as unparsable and save error information
            self._mark_import_queue_as_failed(import_queue_id, validation_message)

            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg,
                error_message=validation_message
            )

            # Broadcast high-level status to realtime channel (processing failed)
            self._broadcast_to_import_queue_module(user_id, 'status_updated', {
                'id': import_queue_id,
                'status': 'failed',
                'progress': 0.0,
                'message': PROCESSING_FAILED
            })

            # Broadcast detailed failure to process status channel
            self._broadcast_to_process_status_module(user_id, import_queue_id, 'item_failed', {
                'job_id': job_id,
                'error_message': error_msg
            })
            return False

        realtime_log.add("File validation passed successfully", "ProcessJob", DatabaseLogLevel.INFO)
        return True

    def _finalize_job_success(self, job_id: str, user_id: int, import_queue_id: int,
                              feature_count: int, overall_duration: float,
                              geojson_data: Dict[str, Any], realtime_log: RealTimeImportLog) -> None:
        """
        Finalize successful job completion with broadcasts and result setting.
        
        Args:
            job_id: Job ID
            user_id: User ID
            import_queue_id: Import queue ID
            feature_count: Number of features processed
            overall_duration: Total processing duration in seconds
            geojson_data: Processed GeoJSON data
            realtime_log: Real-time log
        """
        # Mark as completed
        realtime_log.add_timing("Total file processing", overall_duration, "ProcessJob")

        completion_msg = f"File processing completed! Processed {feature_count} features in {overall_duration:.1f}s"
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.COMPLETED,
            completion_msg, 100.0
        )

        # Broadcast high-level status to realtime channel (processing completed)
        self._broadcast_to_import_queue_module(user_id, 'status_updated', {
            'id': import_queue_id,
            'status': 'completed',
            'progress': 100.0,
            'message': 'Processing completed'
        })

        # Broadcast detailed completion to process status channel
        self._broadcast_to_process_status_module(user_id, import_queue_id, 'item_completed', {
            'job_id': job_id,
            'message': completion_msg
        })
        realtime_log.add(completion_msg, "ProcessJob", DatabaseLogLevel.INFO)

        # Set result data
        self.status_tracker.set_job_result(
            job_id,
            {'geojson_data': geojson_data, 'processing_log': realtime_log},
            import_queue_id
        )

        # Log completion with features and time
        logger.info(f"Job {job_id} completed: {feature_count} features processed in {overall_duration:.1f}s")

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the process job processing logic.
        
        This method signature matches the BaseJob interface but ProcessJob uses
        Redis queue instead of threading, so kwargs is actually the full job_data dict.
        
        Args:
            job_id: Job ID (part of BaseJob interface, also in kwargs)
            kwargs: Job data from Redis queue containing job_id, file_data, filename, user_id
        """
        # Extract job parameters (kwargs is actually job_data from Redis)
        job_data = kwargs
        file_data = job_data['file_data']
        filename = job_data['filename']
        user_id = job_data['user_id']

        # Get job and import queue info
        job = self.status_tracker.get_job(job_id)
        if not job or not job.import_queue_id:
            logger.error(f"Process job {job_id} not found or missing import_queue_id")
            return

        import_queue_id = job.import_queue_id

        # Get the UUID from the ImportQueue for logging
        try:
            import_queue = ImportQueue.objects.get(id=import_queue_id)
            assert import_queue.log_id
            log_uuid = str(import_queue.log_id)
            # Check if this is a replacement upload (fast path)
            is_replacement = import_queue.replacement is not None
        except ImportQueue.DoesNotExist:
            # ImportQueue was deleted (likely by user deletion), stop processing
            logger.warning(f"ImportQueue {import_queue_id} was deleted, stopping processing for job {job_id}")
            return

        # Create real-time logger
        realtime_log = RealTimeImportLog(user_id, log_uuid)

        # Track overall processing time
        overall_start_time = time.time()

        # Queue worker ensures sequential processing, no lock needed
        try:
            # Update status to processing
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "Starting file validation and processing...", 12.0
            )

            # Broadcast high-level status to realtime channel (processing started)
            self._broadcast_to_import_queue_module(user_id, 'status_updated', {
                'id': import_queue_id,
                'status': 'processing',
                'progress': 12.0,
                'message': 'Processing started'
            })

            # Validate file
            if not self._validate_uploaded_file(file_data, filename, job_id, user_id, import_queue_id, realtime_log):
                return
    
            # Check if job was cancelled after validation
            if self._check_cancellation(job_id, import_queue_id, realtime_log, "after validation"):
                return
    
            # Calculate progress percentages based on path type (fast path vs normal)
            validation_progress = 20.0 if is_replacement else 36.0
            conversion_progress = 60.0 if is_replacement else 48.0
    
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "File validation passed, starting conversion...", validation_progress
            )
    
            # Process file to GeoJSON
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "Converting to GeoJSON format...", conversion_progress
            )
            realtime_log.add("Starting GeoJSON conversion", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Check if job was cancelled before conversion
            if self._check_cancellation(job_id, import_queue_id, realtime_log, "before conversion"):
                return
    
            # Get file size for logging
            file_size_mb = len(file_data) / (1024 * 1024)
            realtime_log.add(f"Processing {file_size_mb:.1f}MB file", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Convert to GeoJSON with timing using new processor API
            # Use minimal processing for replacement uploads (skip tags, geocoding)
            conversion_start = time.time()
            logger.info(f"Starting GeoJSON conversion for job {job_id}: file '{filename}' ({file_size_mb:.2f} MB), replacement={is_replacement}")
            processor = get_processor(
                file_data,
                filename,
                job_id=job_id,
                status_tracker=self.status_tracker,
                minimal_processing=is_replacement
            )
            geojson_data, processing_log = processor.process()
    
            if not geojson_data or 'features' not in geojson_data:
                raise FileValidationError("Processor returned invalid GeoJSON data")
    
            logger.info(f"GeoJSON conversion completed for job {job_id} in {time.time() - conversion_start:.2f}s")
            conversion_duration = time.time() - conversion_start
            realtime_log.add_timing("GeoJSON conversion", conversion_duration, "ProcessJob")
    
            # Check if job was cancelled during processing
            if self._check_cancellation(job_id, import_queue_id, realtime_log, "during GeoJSON conversion/processing"):
                return
    
            # Apply user setting: overwrite single track name with filename if enabled
            self._apply_track_name_override(geojson_data, user_id, filename, job_id)
    
            # Add processing log messages to real-time log
            realtime_log.extend(processing_log)
    
            # Prepare GeoJSON string and size for database storage
            geojson_str = json.dumps(geojson_data)
            geojson_size_mb = len(geojson_str) / (1024 * 1024)
    
            # Update progress
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "Processing features...", 60.0
            )
            realtime_log.add("Processing features", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Process features and update import queue entry
            self._update_and_broadcast_status(
                job_id, user_id, import_queue_id,
                "Updating database entry...", 72.0
            )
            realtime_log.add("Updating database entry", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Count features for logging
            feature_count = len(geojson_data.get('features', []))
            realtime_log.add(f"Found {feature_count} features to process", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Check if job was cancelled before database update
            if self._check_cancellation(job_id, import_queue_id, realtime_log, "before database update"):
                return
    
            # Finalize processed features with duplicate detection and save
            feature_processing_start = time.time()
            import_queue_id = self._finalize_and_save_processed_features(
                geojson_data, realtime_log, filename, user_id, job_id, geojson_str, geojson_size_mb, file_data
            )
            feature_processing_duration = time.time() - feature_processing_start
            realtime_log.add_timing("Feature processing and database update", feature_processing_duration, "ProcessJob")
    
            # Finalize job success
            overall_duration = time.time() - overall_start_time
            self._finalize_job_success(job_id, user_id, import_queue_id, feature_count, 
                                      overall_duration, geojson_data, realtime_log)
    
        except TimeoutError as e:
            # Timeout during processing (e.g., subprocess timeout)
            error_msg = str(e)
            logger.error(f"Processing timeout for job {job_id}: {error_msg}")
            realtime_log.add(error_msg, "ProcessJob", DatabaseLogLevel.ERROR)
            self._handle_processing_error(job_id, user_id, error_msg, error_msg, realtime_log)
            
        except (SecurityError, FileValidationError) as e:
            # Use the error message directly from the validation
            error_msg = f"{FILE_VALIDATION_FAILED}: {str(e)}"
            # Log detailed error internally for debugging
            logger.error(f"Security error in job {job_id}: {str(e)}")
            self._handle_processing_error(job_id, user_id, error_msg, str(e), realtime_log)

        except subprocess.TimeoutExpired:
            error_msg = PROCESSING_TIMEOUT
            logger.error(f"Processing timeout for job {job_id}")
            self._handle_processing_error(job_id, user_id, error_msg, error_msg, realtime_log)

        except Exception as e:
            # Generic error message for users, detailed logging internally
            error_msg = ERROR_OCCURRED_DURING_PROCESSING
            # Get file info for better error context
            file_size_mb = len(file_data) / (1024 * 1024) if file_data else 0
            # Log detailed error internally only (not exposed to user via RealTimeImportLog)
            logger.error(f"Processing error in job {job_id} for file '{filename}' ({file_size_mb:.2f} MB): {type(e).__name__}: {str(e)}")
            logger.error(f"Full traceback for job {job_id}: {traceback.format_exc()}")
            self._handle_processing_error(job_id, user_id, error_msg, error_msg, realtime_log)

    def _create_initial_import_queue_entry(self, filename: str, user_id: int, job_id: str, replacement_feature_id: Optional[int] = None) -> int:
        """Create an initial ImportQueue entry for async processing."""
        try:
            with transaction.atomic():
                # Get user
                user = User.objects.get(id=user_id)

                # Create import queue entry with empty geofeatures during processing
                import_queue = ImportQueue.objects.create(
                    raw_file='{"type": "FeatureCollection", "features": []}',  # Empty GeoJSON
                    original_filename=filename,
                    user=user,
                    geofeatures=[],  # Empty array during processing
                    replacement=replacement_feature_id  # Set replacement feature ID if provided
                )

                return import_queue.id

        except Exception as e:
            logger.error(f"Failed to create initial import queue entry for job {job_id}: {str(e)}")
            logger.error(f"Import queue creation error traceback: {traceback.format_exc()}")
            raise

    def _check_cancellation(self, job_id: str, import_queue_id: int, 
                           processing_log: RealTimeImportLog, stage: str) -> bool:
        """
        Check if job was cancelled.
        
        Args:
            job_id: The job ID to check
            import_queue_id: The import queue ID (for return value)
            processing_log: Log to add cancellation message to
            stage: Description of processing stage for logging
            
        Returns:
            True if job was cancelled, False otherwise
        """
        job = self.status_tracker.get_job(job_id)
        if job and job.status == ProcessingStatus.CANCELLED:
            logger.info(f"Job {job_id} was cancelled {stage}")
            processing_log.add(f"Processing cancelled {stage}", "ProcessJob", DatabaseLogLevel.WARNING)
            return True
        return False

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
        
        self._broadcast_to_process_status_module(user_id, import_queue_id, 'status_updated', {
            'status': 'processing',
            'progress': progress,
            'message': message
        })

    def _build_duplicate_summary(self, fs_hash_count: int, fs_geom_count: int,
                                cq_hash_count: int, cq_geom_count: int) -> str:
        """
        Build duplicate summary message for logging.
        
        Args:
            fs_hash_count: Feature store hash duplicate count
            fs_geom_count: Feature store geometry duplicate count
            cq_hash_count: Cross-queue hash duplicate count
            cq_geom_count: Cross-queue geometry duplicate count
            
        Returns:
            Summary string like "Found 5 duplicate(s): 3 in library, 2 in import queue"
        """
        total_existing_duplicates = fs_hash_count + fs_geom_count + cq_hash_count + cq_geom_count
        
        if total_existing_duplicates == 0:
            return "No duplicates found in library or import queue"
        
        summary_parts = []
        if fs_hash_count > 0 or fs_geom_count > 0:
            fs_total = fs_hash_count + fs_geom_count
            summary_parts.append(f"{fs_total} in library")
        if cq_hash_count > 0 or cq_geom_count > 0:
            cq_total = cq_hash_count + cq_geom_count
            summary_parts.append(f"{cq_total} in import queue")
        
        return f"Found {total_existing_duplicates} duplicate(s): {', '.join(summary_parts)}"

    def _finalize_and_save_processed_features(self, geojson_data: Dict[str, Any],
                                   processing_log: RealTimeImportLog, filename: str,
                                   user_id: int, job_id: str, geojson_str: str, geojson_size_mb: float,
                                   raw_file_data: bytes) -> int:
        """
        Finalize processed features with duplicate detection and save to database.
        
        This is the final processing step that:
        - Performs duplicate detection (internal, feature store, cross-queue)
        - Handles file-level duplicate checking
        - Auto-skips geometry duplicates
        - Persists all data to the ImportQueue entry
        
        Args:
            geojson_data: Processed GeoJSON data
            processing_log: Real-time import log
            filename: Original filename
            user_id: User ID
            job_id: Processing job ID
            geojson_str: GeoJSON as string
            geojson_size_mb: Size of GeoJSON in MB
            raw_file_data: Raw file content as bytes
            
        Returns:
            ImportQueue entry ID
        """
        # Get the import queue entry
        job = self.status_tracker.get_job(job_id)
        if not job or not job.import_queue_id:
            raise Exception("No import queue ID found for job")

        try:
            import_queue = ImportQueue.objects.get(id=job.import_queue_id)
        except ImportQueue.DoesNotExist:
            # ImportQueue was deleted (likely by user deletion), stop processing
            logger.warning(f"ImportQueue {job.import_queue_id} was deleted during processing, stopping for job {job_id}")
            return job.import_queue_id  # Return the ID even though we can't update it

        try:
            # Hash the raw file content for duplicate detection OUTSIDE the transaction
            # This ensures files with the same source content get the same hash,
            # regardless of processing differences or file format (KML vs KMZ)
            raw_file_content, file_hash = encode_raw_file_data(raw_file_data)

            # Process features using the processor's already processed features
            features = geojson_data.get('features', [])

            processing_log.add(f"Processing {len(features)} features from uploaded file", "ProcessJob", DatabaseLogLevel.INFO)
            # Features are already processed by the processor, so we use them directly
            processed_features = features
            
            # Pre-calculate and inject geojson_hash into properties
            inject_feature_hashes(processed_features)
            
            # Log feature type breakdown
            type_summary = build_feature_type_summary(processed_features)
            processing_log.add(f"Feature breakdown: {type_summary}", "ProcessJob", DatabaseLogLevel.INFO)
            processing_log.add(f"Successfully processed {len(processed_features)} features", "ProcessJob", DatabaseLogLevel.INFO)
            processing_log.add("Preparing to save processed data to database", "ProcessJob", DatabaseLogLevel.INFO)

            # Check if this is a replacement upload - skip duplicate detection for fast path
            is_replacement = import_queue.replacement is not None

            with transaction.atomic():

                if is_replacement:
                    # Fast path: skip duplicate detection entirely for replacement uploads
                    processing_log.add("Skipping duplicate detection for replacement upload (fast path)", "ProcessJob", DatabaseLogLevel.INFO)
                    duplicate_features = []  # No duplicates tracked for replacements
                else:
                    # Normal path: perform duplicate detection
                    # Check for cancellation before duplicate detection
                    if self._check_cancellation(job_id, import_queue.id, processing_log, "before duplicate detection"):
                        return import_queue.id

                    # Update progress for duplicate detection
                    self._update_and_broadcast_status(
                        job_id, user_id, import_queue.id,
                        "Checking for duplicate features...", 84.0
                    )

                    # Start duplicate detection
                    duplicate_detection_start = time.time()

                    # First, check for internal duplicates within the file
                    unique_internal_features, internal_duplicate_count = strip_duplicate_features(processed_features)
                    
                    if internal_duplicate_count > 0:
                        processing_log.add(f"Found {internal_duplicate_count} internal duplicate(s)", "ProcessJob", DatabaseLogLevel.INFO)
                    else:
                        processing_log.add("No internal duplicates found", "ProcessJob", DatabaseLogLevel.INFO)

                    # Check for cancellation after internal duplicate detection
                    if self._check_cancellation(job_id, import_queue.id, processing_log, "after internal duplicate detection"):
                        return import_queue.id

                    # PASS 1: Check feature store (hash + geometry, with hash priority)
                    remaining_after_fs, feature_store_duplicates, fs_log = find_duplicates_for_source(
                        unique_internal_features,
                        user_id,
                        source='feature_store',
                        exclude_queue_id=None,
                        exclude_timestamp=None
                    )
                    
                    # Split feature store duplicates into hash and geometry for tracking
                    feature_store_hash_duplicates, feature_store_geom_duplicates = split_duplicates_by_match_type(
                        feature_store_duplicates
                    )
                    
                    # PASS 2: Check cross-queue (hash + geometry, with hash priority) on remaining features
                    remaining_after_cq, cross_queue_duplicates, cq_log = find_duplicates_for_source(
                        remaining_after_fs,
                        user_id,
                        source='cross_queue',
                        exclude_queue_id=import_queue.id,
                        exclude_timestamp=import_queue.timestamp
                    )
                    
                    # Split cross-queue duplicates into hash and geometry for tracking
                    cross_queue_hash_duplicates, cross_queue_geom_duplicates = split_duplicates_by_match_type(
                        cross_queue_duplicates
                    )
                    
                    # Combine all duplicates in priority order
                    duplicate_features = (
                        feature_store_hash_duplicates + 
                        feature_store_geom_duplicates + 
                        cross_queue_hash_duplicates + 
                        cross_queue_geom_duplicates
                    )
                    
                    # Log duplicate detection results summary
                    fs_hash_count = len(feature_store_hash_duplicates)
                    fs_geom_count = len(feature_store_geom_duplicates)
                    cq_hash_count = len(cross_queue_hash_duplicates)
                    cq_geom_count = len(cross_queue_geom_duplicates)
                    
                    summary = self._build_duplicate_summary(fs_hash_count, fs_geom_count, cq_hash_count, cq_geom_count)
                    processing_log.add(summary, "ProcessJob", DatabaseLogLevel.INFO)
                    
                    duplicate_detection_duration = time.time() - duplicate_detection_start
                    processing_log.add(f"Duplicate detection completed ({duplicate_detection_duration:.1f}s)", "ProcessJob", DatabaseLogLevel.INFO)

                    # Check for cancellation after duplicate detection
                    if self._check_cancellation(job_id, import_queue.id, processing_log, "after duplicate detection"):
                        return import_queue.id

                # Check for cancellation before database save
                if self._check_cancellation(job_id, import_queue.id, processing_log, "before database save"):
                    return import_queue.id

                # Update progress for database save (different percentages for fast vs normal path)
                if is_replacement:
                    # Fast path: already at 100% since we skipped duplicate detection
                    progress = 100.0
                    message = "Saving features to database..."
                else:
                    # Normal path: 96% after duplicate detection
                    progress = 96.0
                    message = "Saving features to database..."

                self._update_and_broadcast_status(
                    job_id, user_id, import_queue.id,
                    message, progress
                )

                # Save the features to the database
                processing_log.add(f"Saving {len(processed_features)} features to database ({geojson_size_mb:.2f} MB)", "ProcessJob", DatabaseLogLevel.INFO)

                # Convert features through Pydantic models for validation and serialization
                # This ensures datetime objects are serialized to ISO strings via model_dump(mode='json')
                # and geometry objects are converted to GeoJSON dicts
                import_queue.raw_file = raw_file_content
                
                # CRITICAL SECTION: Use advisory lock to prevent race conditions when saving file hash
                # This ensures that if two identical files are uploaded simultaneously, one will
                # be properly marked as a duplicate of the other
                with advisory_lock(file_hash):
                    # Check for file-level duplicates AFTER acquiring lock
                    # This ensures the hash from the first file is saved before the second file checks
                    duplicate_imported_file = ImportQueue.objects.filter(
                        user_id=user_id,
                        file_hash=file_hash,
                        imported=True
                    ).exclude(id=import_queue.id).first()
                    
                    if duplicate_imported_file and not is_replacement:
                        # This file is a duplicate of an already-imported file
                        processing_log.add(
                            f"File is a duplicate of already imported file: {duplicate_imported_file.original_filename}",
                            "File Duplicate Detection",
                            DatabaseLogLevel.WARNING
                        )
                        # Note: We still save the file but don't set any special status here
                        # The WebSocket module will detect this and auto-recheck duplicates
                    
                    # Save the hash and all other data
                    import_queue.file_hash = file_hash
                    import_queue.geofeatures = convert_features_to_pydantic(processed_features)
                    import_queue.duplicate_features = convert_features_to_pydantic(duplicate_features)
                    
                    # Auto-skip ONLY geometry duplicates by adding their feature IDs to skipped_feature_ids
                    # Hash duplicates are always blocked and should not be in skipped_feature_ids
                    existing_skipped = set(import_queue.skipped_feature_ids if import_queue.skipped_feature_ids else [])
                    import_queue.skipped_feature_ids = build_skipped_feature_ids(duplicate_features, existing_skipped)
                    import_queue.save()
                # Advisory lock released here

                processing_log.add("Import queue entry updated successfully", "ProcessJob", DatabaseLogLevel.INFO)

                # Broadcast status update to trigger queue refresh so duplicate status is updated
                self._broadcast_to_import_queue_module(user_id, 'status_updated', {'id': import_queue.id})

                # Note: No need to call importlog_to_db since RealTimeImportLog writes to DB immediately

                return import_queue.id

        except Exception as e:
            logger.error(f"Failed to update import queue entry for job {job_id}: {str(e)}")
            raise

    def _broadcast_to_import_queue_module(self, user_id: int, event_type: str, data: dict):
        """Broadcast WebSocket event to import_queue module."""
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"realtime_{user_id}",
                {
                    'type': f'import_queue_{event_type}',
                    'data': data
                }
            )

    def _broadcast_item_added(self, user_id: int, import_queue_id: int):
        """Broadcast WebSocket event when a new item is added to import queue."""
        self._broadcast_to_import_queue_module(user_id, 'item_added', {'id': import_queue_id})

    def _broadcast_to_process_status_module(self, user_id: int, import_queue_id: int, event_type: str, data: dict):
        """Broadcast WebSocket event to process_status module for specific item."""
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"process_status_{user_id}_{import_queue_id}",
                {
                    'type': event_type,
                    'data': data
                }
            )
