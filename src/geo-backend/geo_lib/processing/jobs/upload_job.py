"""
Upload job processor for asynchronous file processing.
"""

import subprocess
import time
import traceback
from typing import Dict, Any, Optional

from django.contrib.auth.models import User
from django.db import transaction

from api.models import ImportQueue
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
from geo_lib.processing.processors import get_processor
from geo_lib.processing.status_tracker import ProcessingStatus
from geo_lib.security.file_validation import SecureFileValidator, SecurityError, FileValidationError
from geo_lib.logging.console import get_import_logger

logger = get_import_logger()


class UploadJob(BaseJob):
    """
    Handles asynchronous processing of uploaded files.
    Refactored from AsyncFileProcessor to use the new job system.
    """

    def get_job_type(self) -> str:
        return "upload"

    def start_upload_job(self, job_id: str, file_data: bytes, filename: str, user_id: int, replacement_feature_id: Optional[int] = None) -> bool:
        """
        Start processing a file in a background thread.
        
        Args:
            job_id: Unique job identifier
            file_data: File content as bytes
            filename: Original filename
            user_id: ID of the user who uploaded the file
            replacement_feature_id: Optional ID of the feature being updated (for replacement uploads)
            
        Returns:
            True if processing started successfully, False otherwise
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

        # Start the job
        return self.start_job(job_id, file_data=file_data, filename=filename, user_id=user_id)

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the upload job processing logic.
        """
        file_data = kwargs['file_data']
        filename = kwargs['filename']
        user_id = kwargs['user_id']

        # Get the job for user info
        job = self.status_tracker.get_job(job_id)
        if not job:
            logger.error(f"Upload job {job_id} not found")
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

            # Broadcast detailed status to upload status channel
            self._broadcast_to_upload_status_module(user_id, import_queue_id, 'status_updated', {
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
            self._broadcast_to_upload_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': 24.0,
                'message': 'Validating file format and security...'
            })
            realtime_log.add("Validating file format and security", "UploadJob", DatabaseLogLevel.INFO)

            # Create a mock uploaded file for validation
            from django.core.files.uploadedfile import SimpleUploadedFile
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
            realtime_log.add_timing("File validation", validation_duration, "UploadJob")

            if not is_valid:
                error_msg = f"File validation failed: {validation_message}"
                realtime_log.add(error_msg, "UploadJob", DatabaseLogLevel.ERROR)
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
                    'message': 'Processing failed'
                })

                # Broadcast detailed failure to upload status channel
                self._broadcast_to_upload_status_module(user_id, import_queue_id, 'item_failed', {
                    'job_id': job_id,
                    'error_message': error_msg
                })
                return

            realtime_log.add("File validation passed successfully", "UploadJob", DatabaseLogLevel.INFO)

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
            self._broadcast_to_upload_status_module(user_id, import_queue_id, 'status_updated', {
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
            self._broadcast_to_upload_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': conversion_progress,
                'message': 'Converting to GeoJSON format...'
            })
            realtime_log.add("Starting GeoJSON conversion", "UploadJob", DatabaseLogLevel.INFO)

            # Check if job was cancelled before conversion
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                return

            # Get file size for logging
            file_size_mb = len(file_data) / (1024 * 1024)
            realtime_log.add(f"Processing {file_size_mb:.1f}MB file", "UploadJob", DatabaseLogLevel.INFO)

            # Convert to GeoJSON with timing using new processor API
            # Use minimal processing for replacement uploads (skip tags, geocoding)
            conversion_start = time.time()
            processor = get_processor(
                file_data, 
                filename, 
                job_id=job_id, 
                status_tracker=self.status_tracker,
                minimal_processing=is_replacement
            )
            geojson_data, processing_log = processor.process()
            conversion_duration = time.time() - conversion_start
            realtime_log.add_timing("GeoJSON conversion", conversion_duration, "UploadJob")

            # Check if job was cancelled during processing
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                logger.info(f"Job {job_id} was cancelled during GeoJSON conversion/processing")
                return

            # Add processing log messages to real-time log
            realtime_log.extend(processing_log)

            # Prepare GeoJSON string and size for database storage
            import json
            geojson_str = json.dumps(geojson_data)
            geojson_size_mb = len(geojson_str) / (1024 * 1024)

            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Processing features...", 60.0
            )

            # Broadcast WebSocket event for status update
            self._broadcast_to_upload_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': 60.0,
                'message': 'Processing features...'
            })
            realtime_log.add("Processing features", "UploadJob", DatabaseLogLevel.INFO)

            # Process features and update import queue entry
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Updating database entry...", 72.0
            )

            # Broadcast WebSocket event for status update
            self._broadcast_to_upload_status_module(user_id, import_queue_id, 'status_updated', {
                'status': 'processing',
                'progress': 72.0,
                'message': 'Updating database entry...'
            })
            realtime_log.add("Updating database entry", "UploadJob", DatabaseLogLevel.INFO)

            # Count features for logging
            feature_count = len(geojson_data.get('features', []))
            realtime_log.add(f"Found {feature_count} features to process", "UploadJob", DatabaseLogLevel.INFO)

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
            realtime_log.add_timing("Feature processing and database update", feature_processing_duration, "UploadJob")

            # Mark as completed
            overall_duration = time.time() - overall_start_time
            realtime_log.add_timing("Total file processing", overall_duration, "UploadJob")

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

            # Broadcast detailed completion to upload status channel
            self._broadcast_to_upload_status_module(user_id, import_queue_id, 'item_completed', {
                'job_id': job_id,
                'message': completion_msg
            })
            realtime_log.add(completion_msg, "UploadJob", DatabaseLogLevel.INFO)

            # Set result data
            self.status_tracker.set_job_result(
                job_id,
                {'geojson_data': geojson_data, 'processing_log': realtime_log},
                import_queue_id
            )

            # Log completion with features and time
            logger.info(f"Job {job_id} completed: {feature_count} features processed in {overall_duration:.1f}s")

        except (SecurityError, FileValidationError) as e:
            # Use the error message directly from the validation
            error_msg = f"File validation failed: {str(e)}"
            # Log detailed error internally for debugging
            logger.error(f"Security error in job {job_id}: {str(e)}")
            realtime_log.add(error_msg, "UploadJob", DatabaseLogLevel.ERROR)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=str(e)
            )

            # Broadcast high-level status to realtime channel (processing failed)
            job = self.status_tracker.get_job(job_id)
            if job and job.import_queue_id:
                self._broadcast_to_import_queue_module(user_id, 'status_updated', {
                    'id': job.import_queue_id,
                    'status': 'failed',
                    'progress': 0.0,
                    'message': 'Processing failed'
                })

                # Broadcast detailed failure to upload status channel
                self._broadcast_to_upload_status_module(user_id, job.import_queue_id, 'item_failed', {
                    'job_id': job_id,
                    'error_message': error_msg
                })

        except subprocess.TimeoutExpired:
            error_msg = "File processing timed out: file may be too large or complex"
            logger.error(f"Processing timeout for job {job_id}")
            realtime_log.add(error_msg, "UploadJob", DatabaseLogLevel.ERROR)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )

            # Broadcast high-level status to realtime channel (processing failed)
            job = self.status_tracker.get_job(job_id)
            if job and job.import_queue_id:
                self._broadcast_to_import_queue_module(user_id, 'status_updated', {
                    'id': job.import_queue_id,
                    'status': 'failed',
                    'progress': 0.0,
                    'message': 'Processing failed'
                })

                # Broadcast detailed failure to upload status channel
                self._broadcast_to_upload_status_module(user_id, job.import_queue_id, 'item_failed', {
                    'job_id': job_id,
                    'error_message': error_msg
                })

        except Exception as e:
            # Generic error message for users, detailed logging internally
            error_msg = "An error occurred during file processing"
            # Log detailed error internally only (not exposed to user via RealTimeImportLog)
            logger.error(f"Processing error in job {job_id}: {type(e).__name__}: {str(e)}")
            logger.error(f"Full traceback for job {job_id}: {traceback.format_exc()}")
            realtime_log.add(error_msg, "UploadJob", DatabaseLogLevel.ERROR)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )

            # Broadcast high-level status to realtime channel (processing failed)
            job = self.status_tracker.get_job(job_id)
            if job and job.import_queue_id:
                self._broadcast_to_import_queue_module(user_id, 'status_updated', {
                    'id': job.import_queue_id,
                    'status': 'failed',
                    'progress': 0.0,
                    'message': 'Processing failed'
                })

                # Broadcast detailed failure to upload status channel
                self._broadcast_to_upload_status_module(user_id, job.import_queue_id, 'item_failed', {
                    'job_id': job_id,
                    'error_message': error_msg
                })

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
            with transaction.atomic():

                # Hash the raw file content for duplicate detection
                # This ensures files with the same source content get the same hash,
                # regardless of processing differences or file format (KML vs KMZ)
                import hashlib
                if isinstance(raw_file_data, str):
                    raw_file_data = raw_file_data.encode('utf-8')
                file_hash = hashlib.sha256(raw_file_data).hexdigest()

                # Process features using the processor's already processed features
                features = geojson_data.get('features', [])

                processing_log.add(f"Processing {len(features)} features from uploaded file", "UploadJob", DatabaseLogLevel.INFO)
                # Features are already processed by the processor, so we use them directly
                processed_features = features

                # Log feature type breakdown
                feature_types = {}
                for feature in processed_features:
                    geom_type = feature.get('geometry', {}).get('type', 'Unknown')
                    feature_types[geom_type] = feature_types.get(geom_type, 0) + 1

                type_summary = ', '.join([f"{count} {ftype}" for ftype, count in feature_types.items()])
                processing_log.add(f"Feature breakdown: {type_summary}", "UploadJob", DatabaseLogLevel.INFO)
                processing_log.add(f"Successfully processed {len(processed_features)} features", "UploadJob", DatabaseLogLevel.INFO)
                processing_log.add("Preparing to save processed data to database", "UploadJob", DatabaseLogLevel.INFO)

                # Store the raw file hash for duplicate detection
                # Note: field is named geojson_hash for historical reasons, but stores raw file hash
                geojson_hash = file_hash

                # Check if this is a replacement upload - skip duplicate detection for fast path
                is_replacement = import_queue.replacement is not None
                
                if is_replacement:
                    # Fast path: skip duplicate detection entirely for replacement uploads
                    processing_log.add("Skipping duplicate detection for replacement upload (fast path)", "UploadJob", DatabaseLogLevel.INFO)
                    duplicate_features = []  # No duplicates tracked for replacements
                else:
                    # Normal path: perform duplicate detection
                    # Check for cancellation before duplicate detection
                    job = self.status_tracker.get_job(job_id)
                    if job and job.status == ProcessingStatus.CANCELLED:
                        logger.info(f"Job {job_id} was cancelled before duplicate detection")
                        processing_log.add("Processing cancelled before duplicate detection", "UploadJob", DatabaseLogLevel.WARNING)
                        return import_queue.id

                    # Update progress for duplicate detection
                    self.status_tracker.update_job_status(
                        job_id, ProcessingStatus.PROCESSING,
                        "Checking for duplicate features...", 84.0
                    )

                    # Broadcast WebSocket event for status update
                    self._broadcast_to_upload_status_module(user_id, import_queue.id, 'status_updated', {
                        'status': 'processing',
                        'progress': 84.0,
                        'message': 'Checking for duplicate features...'
                    })

                    # Perform duplicate detection against existing features
                    processing_log.add("Starting duplicate detection against existing feature store", "UploadJob", DatabaseLogLevel.INFO)

                    # Import the duplicate detection functions
                    from api.views.import_item import find_coordinate_duplicates, strip_duplicate_features

                    # First, check for internal duplicates within the file
                    processing_log.add("Checking for internal duplicates within the uploaded file", "UploadJob", DatabaseLogLevel.INFO)
                    unique_internal_features, internal_duplicate_count, internal_duplicate_log = strip_duplicate_features(processed_features)
                    processing_log.extend(internal_duplicate_log)

                    # Check for cancellation after internal duplicate detection
                    job = self.status_tracker.get_job(job_id)
                    if job and job.status == ProcessingStatus.CANCELLED:
                        logger.info(f"Job {job_id} was cancelled after internal duplicate detection")
                        processing_log.add("Processing cancelled after internal duplicate detection", "UploadJob", DatabaseLogLevel.WARNING)
                        return import_queue.id

                    # Then check for coordinate duplicates against existing features
                    processing_log.add("Checking for coordinate duplicates against existing features in your library", "UploadJob", DatabaseLogLevel.INFO)
                    duplicate_detection_start = time.time()
                    unique_features, duplicate_features, duplicate_log = find_coordinate_duplicates(unique_internal_features, user_id)
                    duplicate_detection_duration = time.time() - duplicate_detection_start
                    processing_log.extend(duplicate_log)
                    processing_log.add_timing("Duplicate detection", duplicate_detection_duration, "UploadJob")

                    # Check for cancellation after duplicate detection
                    job = self.status_tracker.get_job(job_id)
                    if job and job.status == ProcessingStatus.CANCELLED:
                        logger.info(f"Job {job_id} was cancelled after duplicate detection")
                        processing_log.add("Processing cancelled after duplicate detection", "UploadJob", DatabaseLogLevel.WARNING)
                        return import_queue.id

                    # Log summary of duplicate detection results
                    total_duplicates = internal_duplicate_count + len(duplicate_features)
                    processing_log.add(f"Duplicate detection completed: {internal_duplicate_count} internal duplicates, {len(duplicate_features)} existing duplicates", "UploadJob", DatabaseLogLevel.INFO)

                    # Use the original processed_features (not unique_features) to preserve all features
                    # The duplicate_features list contains the duplicate information we need
                    processing_log.add(f"Total duplicate features found: {total_duplicates}", "UploadJob", DatabaseLogLevel.INFO)

                # Check for cancellation before database save
                job = self.status_tracker.get_job(job_id)
                if job and job.status == ProcessingStatus.CANCELLED:
                    logger.info(f"Job {job_id} was cancelled before database save")
                    processing_log.add("Processing cancelled before database save", "UploadJob", DatabaseLogLevel.WARNING)
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
                self._broadcast_to_upload_status_module(user_id, import_queue.id, 'status_updated', {
                    'status': 'processing',
                    'progress': progress,
                    'message': message
                })

                # Save the features to the database
                processing_log.add(f"Saving {len(processed_features)} features to database ({geojson_size_mb:.2f} MB)", "UploadJob", DatabaseLogLevel.INFO)

                # Store raw file content (convert bytes to string if needed)
                if isinstance(raw_file_data, bytes):
                    # Try to decode as UTF-8, fall back to base64 if it's binary
                    try:
                        raw_file_content = raw_file_data.decode('utf-8')
                    except UnicodeDecodeError:
                        # For binary files like KMZ, store as base64
                        import base64
                        raw_file_content = base64.b64encode(raw_file_data).decode('utf-8')
                else:
                    raw_file_content = raw_file_data
                
                import_queue.raw_file = raw_file_content
                import_queue.geojson_hash = geojson_hash
                import_queue.geofeatures = processed_features
                import_queue.duplicate_features = duplicate_features  # Store duplicate information
                import_queue.save()

                processing_log.add("Import queue entry updated successfully", "UploadJob", DatabaseLogLevel.INFO)

                # Broadcast status update to trigger queue refresh so duplicate status is updated
                self._broadcast_to_import_queue_module(user_id, 'status_updated', {'id': import_queue.id})

                # Note: No need to call importlog_to_db since RealTimeImportLog writes to DB immediately

                return import_queue.id

        except Exception as e:
            logger.error(f"Failed to update import queue entry for job {job_id}: {str(e)}")
            logger.error(f"Import queue update error traceback: {traceback.format_exc()}")
            raise

    def _broadcast_to_import_queue_module(self, user_id: int, event_type: str, data: dict):
        """Broadcast WebSocket event to import_queue module."""
        from channels.layers import get_channel_layer
        from asgiref.sync import async_to_sync

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

    def _broadcast_to_upload_status_module(self, user_id: int, import_queue_id: int, event_type: str, data: dict):
        """Broadcast WebSocket event to upload_status module for specific item."""
        from channels.layers import get_channel_layer
        from asgiref.sync import async_to_sync

        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"upload_status_{user_id}_{import_queue_id}",
                {
                    'type': event_type,
                    'data': data
                }
            )