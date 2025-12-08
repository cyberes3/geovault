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
from geo_lib.security.file_validation import SecureFileValidator, SecurityError, FileValidationError
from geo_lib.utils.pydantic_serialization import convert_features_to_pydantic
from geo_lib.utils.advisory_locks import advisory_lock

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
    
    def process_from_queue(self, job_data: Dict[str, Any]):
        """
        Process a job that was dequeued from Redis.
        Called by the queue worker thread.
        
        Args:
            job_data: Dictionary containing job information
        """
        job_id = job_data['job_id']
        file_data = job_data['file_data']
        filename = job_data['filename']
        user_id = job_data['user_id']
        
        # Execute the job directly (no threading, worker handles that)
        kwargs = {
            'file_data': file_data,
            'filename': filename,
            'user_id': user_id
        }
        self._execute_job(job_id, kwargs)

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

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the process job processing logic.
        """
        file_data = kwargs['file_data']
        filename = kwargs['filename']
        user_id = kwargs['user_id']

        # Get the job for user info
        job = self.status_tracker.get_job(job_id)
        if not job:
            logger.error(f"Process job {job_id} not found")
            return

        # Get the import queue ID for logging
        import_queue_id = job.import_queue_id if job else None

        # Get the UUID from the ImportQueue for logging
        assert import_queue_id
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
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Starting file validation and processing...", 12.0
            )

            # Broadcast high-level status to realtime channel (processing started)
            self._broadcast_to_import_queue_module(user_id, 'status_updated', {
                'id': import_queue_id,
                'status': 'processing',
                'progress': 12.0,
                'message': 'Processing started'
            })

            # Broadcast detailed status to process status channel
            self._broadcast_to_process_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': 12.0,
                'message': 'Starting file validation and processing...'
            })

            # Validate file
            self.status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            "Validating file format and security...", 24.0
            )

            # Broadcast WebSocket event for status update
            self._broadcast_to_process_status_module(user_id, import_queue_id, 'status_updated', {
            'status': 'processing',
            'progress': 24.0,
            'message': 'Validating file format and security...'
            })
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
                return
    
            realtime_log.add("File validation passed successfully", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Check if job was cancelled after validation
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                return
    
            # Update progress (different percentages for fast vs normal path)
            if is_replacement:
                # Fast path: validation 20%, conversion 60%
                validation_progress = 20.0
                conversion_progress = 60.0
            else:
                # Normal path: validation 36%, conversion 48%
                validation_progress = 36.0
                conversion_progress = 48.0
    
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "File validation passed, starting conversion...", validation_progress
            )
    
            # Broadcast WebSocket event for status update
            self._broadcast_to_process_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': validation_progress,
                'message': 'File validation passed, starting conversion...'
            })
    
            # Process file to GeoJSON
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Converting to GeoJSON format...", conversion_progress
            )
    
            # Broadcast WebSocket event for status update
            self._broadcast_to_process_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': conversion_progress,
                'message': 'Converting to GeoJSON format...'
            })
            realtime_log.add("Starting GeoJSON conversion", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Check if job was cancelled before conversion
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
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
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                logger.info(f"Job {job_id} was cancelled during GeoJSON conversion/processing")
                return
    
            # Apply user setting: overwrite single track name with filename if enabled
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
    
            # Add processing log messages to real-time log
            realtime_log.extend(processing_log)
    
            # Prepare GeoJSON string and size for database storage
            geojson_str = json.dumps(geojson_data)
            geojson_size_mb = len(geojson_str) / (1024 * 1024)
    
            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Processing features...", 60.0
            )
    
            # Broadcast WebSocket event for status update
            self._broadcast_to_process_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': 60.0,
                'message': 'Processing features...'
            })
            realtime_log.add("Processing features", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Process features and update import queue entry
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Updating database entry...", 72.0
            )
    
            # Broadcast WebSocket event for status update
            self._broadcast_to_process_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': 72.0,
                'message': 'Updating database entry...'
            })
            realtime_log.add("Updating database entry", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Count features for logging
            feature_count = len(geojson_data.get('features', []))
            realtime_log.add(f"Found {feature_count} features to process", "ProcessJob", DatabaseLogLevel.INFO)
    
            # Check if job was cancelled before database update
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                return
    
            # Update existing import queue entry with timing
            feature_processing_start = time.time()
            import_queue_id = self._update_import_queue_entry(
                geojson_data, realtime_log, filename, user_id, job_id, geojson_str, geojson_size_mb, file_data
            )
            feature_processing_duration = time.time() - feature_processing_start
            realtime_log.add_timing("Feature processing and database update", feature_processing_duration, "ProcessJob")
    
            # Mark as completed
            overall_duration = time.time() - overall_start_time
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

    def _update_import_queue_entry(self, geojson_data: Dict[str, Any],
                                   processing_log: RealTimeImportLog, filename: str,
                                   user_id: int, job_id: str, geojson_str: str, geojson_size_mb: float,
                                   raw_file_data: bytes) -> int:
        """Update an existing ImportQueue entry with processed data."""
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
            if isinstance(raw_file_data, str):
                raw_file_data = raw_file_data.encode('utf-8')
            file_hash = hashlib.sha256(raw_file_data).hexdigest()

            # Process features using the processor's already processed features
            features = geojson_data.get('features', [])

            processing_log.add(f"Processing {len(features)} features from uploaded file", "ProcessJob", DatabaseLogLevel.INFO)
            # Features are already processed by the processor, so we use them directly
            processed_features = features
            
            # Pre-calculate and inject geojson_hash into properties
            # This ensures that the hash used for duplicate detection is preserved
            # and not affected by Pydantic serialization differences later
            for feature in processed_features:
                geojson_hash = generate_geojson_hash(feature)
                if 'properties' not in feature or feature['properties'] is None:
                    feature['properties'] = {}
                feature['properties']['geojson_hash'] = geojson_hash

            # Log feature type breakdown
            feature_types = {}
            for feature in processed_features:
                geom_type = feature.get('geometry', {}).get('type', 'Unknown')
                feature_types[geom_type] = feature_types.get(geom_type, 0) + 1

            type_summary = ', '.join([f"{count} {ftype}" for ftype, count in feature_types.items()])
            processing_log.add(f"Feature breakdown: {type_summary}", "ProcessJob", DatabaseLogLevel.INFO)
            processing_log.add(f"Successfully processed {len(processed_features)} features", "ProcessJob", DatabaseLogLevel.INFO)
            processing_log.add("Preparing to save processed data to database", "ProcessJob", DatabaseLogLevel.INFO)

            # Store the raw file hash for duplicate detection
            file_hash_value = file_hash

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
                    job = self.status_tracker.get_job(job_id)
                    if job and job.status == ProcessingStatus.CANCELLED:
                        logger.info(f"Job {job_id} was cancelled before duplicate detection")
                        processing_log.add("Processing cancelled before duplicate detection", "ProcessJob", DatabaseLogLevel.WARNING)
                        return import_queue.id

                    # Update progress for duplicate detection
                    self.status_tracker.update_job_status(
                        job_id, ProcessingStatus.PROCESSING,
                        "Checking for duplicate features...", 84.0
                    )

                    # Broadcast WebSocket event for status update
                    self._broadcast_to_process_status_module(user_id, import_queue.id, 'status_updated', {
                        'status': 'processing',
                        'progress': 84.0,
                        'message': 'Checking for duplicate features...'
                    })

                    # Perform duplicate detection against existing features
                    processing_log.add("Starting duplicate detection against existing feature store", "ProcessJob", DatabaseLogLevel.INFO)

                    # First, check for internal duplicates within the file
                    processing_log.add("Checking for internal duplicates within the uploaded file", "ProcessJob", DatabaseLogLevel.INFO)
                    unique_internal_features, internal_duplicate_count, internal_duplicate_log = strip_duplicate_features(processed_features)
                    processing_log.extend(internal_duplicate_log)

                    # Check for cancellation after internal duplicate detection
                    job = self.status_tracker.get_job(job_id)
                    if job and job.status == ProcessingStatus.CANCELLED:
                        logger.info(f"Job {job_id} was cancelled after internal duplicate detection")
                        processing_log.add("Processing cancelled after internal duplicate detection", "ProcessJob", DatabaseLogLevel.WARNING)
                        return import_queue.id

                    # Check for duplicates - 2-pass detection with source priority (feature store first, then cross-queue)
                    processing_log.add("Checking for duplicates in your library and import queue", "ProcessJob", DatabaseLogLevel.INFO)
                    duplicate_detection_start = time.time()
                    
                    # PASS 1: Check feature store (hash + geometry, with hash priority)
                    processing_log.add("Checking for duplicates in your feature library", "ProcessJob", DatabaseLogLevel.INFO)
                    remaining_after_fs, feature_store_duplicates, fs_log = find_duplicates_for_source(
                        unique_internal_features,
                        user_id,
                        source='feature_store',
                        exclude_queue_id=None,
                        exclude_timestamp=None
                    )
                    processing_log.extend(fs_log)
                    
                    # Split feature store duplicates into hash and geometry for tracking
                    feature_store_hash_duplicates, feature_store_geom_duplicates = split_duplicates_by_match_type(
                        feature_store_duplicates
                    )
                    
                    # PASS 2: Check cross-queue (hash + geometry, with hash priority) on remaining features
                    processing_log.add("Checking for duplicates in other import queue items", "ProcessJob", DatabaseLogLevel.INFO)
                    remaining_after_cq, cross_queue_duplicates, cq_log = find_duplicates_for_source(
                        remaining_after_fs,
                        user_id,
                        source='cross_queue',
                        exclude_queue_id=import_queue.id,
                        exclude_timestamp=import_queue.timestamp
                    )
                    processing_log.extend(cq_log)
                    
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
                    
                    duplicate_detection_duration = time.time() - duplicate_detection_start
                    processing_log.add_timing("Duplicate detection", duplicate_detection_duration, "ProcessJob")

                    # Check for cancellation after duplicate detection
                    job = self.status_tracker.get_job(job_id)
                    if job and job.status == ProcessingStatus.CANCELLED:
                        logger.info(f"Job {job_id} was cancelled after duplicate detection")
                        processing_log.add("Processing cancelled after duplicate detection", "ProcessJob", DatabaseLogLevel.WARNING)
                        return import_queue.id

                    # Log summary of duplicate detection results
                    total_duplicates = internal_duplicate_count + len(duplicate_features)
                    processing_log.add(f"Duplicate detection completed: {internal_duplicate_count} internal duplicates, {len(duplicate_features)} existing duplicates", "ProcessJob", DatabaseLogLevel.INFO)

                    # Use the original processed_features (not unique_features) to preserve all features
                    # The duplicate_features list contains the duplicate information we need
                    processing_log.add(f"Total duplicate features found: {total_duplicates}", "ProcessJob", DatabaseLogLevel.INFO)

                # Check for cancellation before database save
                job = self.status_tracker.get_job(job_id)
                if job and job.status == ProcessingStatus.CANCELLED:
                    logger.info(f"Job {job_id} was cancelled before database save")
                    processing_log.add("Processing cancelled before database save", "ProcessJob", DatabaseLogLevel.WARNING)
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

                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.PROCESSING,
                    message, progress
                )

                # Broadcast WebSocket event for status update
                self._broadcast_to_process_status_module(user_id, import_queue.id, 'status_updated', {
                    'status': 'processing',
                    'progress': progress,
                    'message': message
                })

                # Save the features to the database
                processing_log.add(f"Saving {len(processed_features)} features to database ({geojson_size_mb:.2f} MB)", "ProcessJob", DatabaseLogLevel.INFO)

                # Store raw file content (convert bytes to string if needed)
                if isinstance(raw_file_data, bytes):
                    # Try to decode as UTF-8, fall back to base64 if it's binary
                    try:
                        raw_file_content = raw_file_data.decode('utf-8')
                    except UnicodeDecodeError:
                        # For binary files like KMZ, store as base64
                        raw_file_content = base64.b64encode(raw_file_data).decode('utf-8')
                else:
                    raw_file_content = raw_file_data

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
                    import_queue.file_hash = file_hash_value
                    import_queue.geofeatures = convert_features_to_pydantic(processed_features)
                    import_queue.duplicate_features = convert_features_to_pydantic(duplicate_features)
                    
                    # Auto-skip ONLY geometry duplicates by adding their feature IDs to skipped_feature_ids
                    # Hash duplicates are always blocked and should not be in skipped_feature_ids
                    existing_skipped = set(import_queue.skipped_feature_ids if import_queue.skipped_feature_ids else [])
                    
                    # Only add geometry duplicates to skipped list
                    for dup in duplicate_features:
                        if dup.get('match_type') == DuplicateMatchType.GEOMETRY:
                            dup_feature = dup.get('feature')
                            if dup_feature:
                                geojson_hash = dup_feature.get('properties', {}).get('geojson_hash')
                                if not geojson_hash:
                                    geojson_hash = generate_geojson_hash(dup_feature)
                                existing_skipped.add(geojson_hash)
                    
                    import_queue.skipped_feature_ids = list(existing_skipped)
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
