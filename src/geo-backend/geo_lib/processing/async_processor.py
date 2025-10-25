"""
Asynchronous file processor for handling large file uploads in the background.
Processes files without blocking the main request thread.
"""

import logging
import subprocess
import threading
import time
import traceback
from typing import Dict, Any

from django.contrib.auth.models import User
from django.db import transaction

from data.models import ImportQueue
from geo_lib.processing.geojson_normalization import hash_normalized_geojson
from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
from geo_lib.processing.processors import get_processor
from geo_lib.processing.status_tracker import ProcessingStatusTracker, ProcessingStatus, status_tracker
from geo_lib.security.file_validation import SecureFileValidator, SecurityError, FileValidationError

logger = logging.getLogger(__name__)


class AsyncFileProcessor:
    """
    Handles asynchronous processing of uploaded files.
    Uses threading to process files in the background.
    """

    def __init__(self, status_tracker: ProcessingStatusTracker):
        self.status_tracker = status_tracker
        self._processing_threads: Dict[str, threading.Thread] = {}

    def start_processing(self, job_id: str, file_data: bytes, filename: str, user_id: int) -> bool:
        """
        Start processing a file in a background thread.
        
        Args:
            job_id: Unique job identifier
            file_data: File content as bytes
            filename: Original filename
            user_id: ID of the user who uploaded the file
            
        Returns:
            True if processing started successfully, False otherwise
        """
        # Check if job already exists and is not cancelled
        job = self.status_tracker.get_job(job_id)
        if not job or job.status == ProcessingStatus.CANCELLED:
            logger.warning(f"Cannot start processing for job {job_id}: job not found or cancelled")
            return False

        # Check if already processing
        if job_id in self._processing_threads:
            logger.warning(f"Job {job_id} is already being processed")
            return False

        # Create initial ImportQueue entry so it shows up in the UI
        try:
            import_queue_id = self._create_initial_import_queue_entry(filename, user_id, job_id)
            self.status_tracker.set_job_result(job_id, {}, import_queue_id)
            
            # Broadcast WebSocket event for new item
            self._broadcast_item_added(user_id, import_queue_id)
        except Exception as e:
            logger.error(f"Failed to create initial import queue entry for job {job_id}: {str(e)}")
            logger.error(f"Import queue creation error traceback: {traceback.format_exc()}")
            return False

        # Start processing in background thread
        thread = threading.Thread(
            target=self._process_file_worker,
            args=(job_id, file_data, filename, user_id),
            daemon=True
        )
        thread.start()

        self._processing_threads[job_id] = thread
        logger.info(f"Started background processing for job {job_id}")
        return True

    def _process_file_worker(self, job_id: str, file_data: bytes, filename: str, user_id: int):
        """
        Worker function that runs in a background thread to process the file.
        """
        # Check if job was cancelled before starting processing
        job = self.status_tracker.get_job(job_id)
        if not job or job.status == ProcessingStatus.CANCELLED:
            logger.info(f"Job {job_id} was cancelled before processing started")
            return

        # Get the import queue ID for logging
        import_queue_id = job.import_queue_id if job else None

        # Get the UUID from the ImportQueue for logging
        assert import_queue_id
        try:
            import_queue = ImportQueue.objects.get(id=import_queue_id)
            assert import_queue.log_id
            log_uuid = str(import_queue.log_id)
        except ImportQueue.DoesNotExist:
            # ImportQueue was deleted (likely by user deletion), stop processing
            logger.info(f"ImportQueue {import_queue_id} was deleted, stopping processing for job {job_id}")
            return

        # Create real-time logger
        realtime_log = RealTimeImportLog(user_id, log_uuid)

        # Track overall processing time
        overall_start_time = time.time()

        try:
            # Update status to processing
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Starting file validation and processing...", 10.0
            )
            
            # Broadcast WebSocket event for status update
            self._broadcast_status_updated(user_id, import_queue_id, "processing", 10.0, "Starting file validation and processing...")

            # Validate file
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Validating file format and security...", 20.0
            )
            realtime_log.add("Validating file format and security", "AsyncProcessor", DatabaseLogLevel.INFO)

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
            realtime_log.add_timing("File validation", validation_duration, "AsyncProcessor")

            if not is_valid:
                error_msg = f"File validation failed: {validation_message}"
                realtime_log.add(error_msg, "AsyncProcessor", DatabaseLogLevel.ERROR)
                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.FAILED,
                    error_msg,
                    error_message=validation_message
                )
                
                # Broadcast WebSocket event for processing failure
                self._broadcast_status_updated(user_id, import_queue_id, "failed", 0.0, error_msg)
                return

            realtime_log.add("File validation passed successfully", "AsyncProcessor", DatabaseLogLevel.INFO)

            # Check if job was cancelled after validation
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                logger.info(f"Job {job_id} was cancelled after validation, stopping processing")
                return

            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "File validation passed, starting conversion...", 30.0
            )
            
            # Broadcast WebSocket event for status update
            self._broadcast_status_updated(user_id, import_queue_id, "processing", 30.0, "File validation passed, starting conversion...")

            # Process file to GeoJSON
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Converting to GeoJSON format...", 50.0
            )
            realtime_log.add("Starting GeoJSON conversion", "AsyncProcessor", DatabaseLogLevel.INFO)

            # Check if job was cancelled before conversion
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                logger.info(f"Job {job_id} was cancelled before conversion, stopping processing")
                return

            # Get file size for logging
            file_size_mb = len(file_data) / (1024 * 1024)
            realtime_log.add(f"Processing {file_size_mb:.1f}MB file", "AsyncProcessor", DatabaseLogLevel.INFO)

            # Convert to GeoJSON with timing using new processor API
            conversion_start = time.time()
            processor = get_processor(file_data, filename)
            geojson_data, processing_log = processor.process()
            conversion_duration = time.time() - conversion_start
            realtime_log.add_timing("GeoJSON conversion", conversion_duration, "AsyncProcessor")

            # Add processing log messages to real-time log
            realtime_log.extend(processing_log)

            # Prepare GeoJSON string and size for database storage
            import json
            geojson_str = json.dumps(geojson_data)
            geojson_size_mb = len(geojson_str) / (1024 * 1024)

            # File size validation is now handled consistently in the file validation step
            # No need for additional size checks here since we validate against FILE_TYPE_CONFIGS limits

            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "GeoJSON conversion complete, processing features...", 70.0
            )
            
            # Broadcast WebSocket event for status update
            self._broadcast_status_updated(user_id, import_queue_id, "processing", 70.0, "GeoJSON conversion complete, processing features...")
            realtime_log.add("GeoJSON conversion complete, processing features", "AsyncProcessor", DatabaseLogLevel.INFO)

            # Process features and update import queue entry
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Processing features and updating database entry...", 90.0
            )
            realtime_log.add("Processing features and updating database entry", "AsyncProcessor", DatabaseLogLevel.INFO)

            # Count features for logging
            feature_count = len(geojson_data.get('features', []))
            realtime_log.add(f"Found {feature_count} features to process", "AsyncProcessor", DatabaseLogLevel.INFO)

            # Check if job was cancelled before database update
            job = self.status_tracker.get_job(job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                logger.info(f"Job {job_id} was cancelled before database update, stopping processing")
                return

            # Update existing import queue entry with timing
            feature_processing_start = time.time()
            import_queue_id = self._update_import_queue_entry(
                geojson_data, realtime_log, filename, user_id, job_id, geojson_str, geojson_size_mb
            )
            feature_processing_duration = time.time() - feature_processing_start
            realtime_log.add_timing("Feature processing and database update", feature_processing_duration, "AsyncProcessor")

            # Mark as completed
            overall_duration = time.time() - overall_start_time
            realtime_log.add_timing("Total file processing", overall_duration, "AsyncProcessor")

            completion_msg = f"File processing completed! Processed {feature_count} features in {overall_duration:.1f}s"
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED,
                completion_msg, 100.0
            )
            
            # Broadcast WebSocket event for completion
            self._broadcast_status_updated(user_id, import_queue_id, "completed", 100.0, completion_msg)
            realtime_log.add(completion_msg, "AsyncProcessor", DatabaseLogLevel.INFO)

            # Set result data
            self.status_tracker.set_job_result(
                job_id,
                {'geojson_data': geojson_data, 'processing_log': realtime_log},
                import_queue_id
            )

            logger.info(f"Successfully completed processing for job {job_id}: {feature_count} features")

        except (SecurityError, FileValidationError) as e:
            # Use the error message directly from the validation
            error_msg = f"File validation failed: {str(e)}"
            # Log detailed error internally for debugging
            logger.error(f"Security error in job {job_id}: {str(e)}")
            realtime_log.add(error_msg, "AsyncProcessor", DatabaseLogLevel.ERROR)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=str(e)
            )
            
            # Broadcast WebSocket event for processing failure
            self._broadcast_status_updated(user_id, import_queue_id, "failed", 0.0, error_msg)

        except subprocess.TimeoutExpired:
            error_msg = "File processing timed out: file may be too large or complex"
            logger.error(f"Processing timeout for job {job_id}")
            realtime_log.add(error_msg, "AsyncProcessor", DatabaseLogLevel.ERROR)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )
            
            # Broadcast WebSocket event for processing failure
            self._broadcast_status_updated(user_id, import_queue_id, "failed", 0.0, error_msg)

        except Exception as e:
            # Generic error message for users, detailed logging internally
            error_msg = "An error occurred during file processing"
            # Log detailed error internally only (not exposed to user via RealTimeImportLog)
            logger.error(f"Processing error in job {job_id}: {type(e).__name__}: {str(e)}")
            logger.debug(f"Full traceback for job {job_id}: {traceback.format_exc()}")
            realtime_log.add(error_msg, "AsyncProcessor", DatabaseLogLevel.ERROR)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )
            
            # Broadcast WebSocket event for processing failure
            self._broadcast_status_updated(user_id, import_queue_id, "failed", 0.0, error_msg)

        finally:
            # Clean up thread reference
            if job_id in self._processing_threads:
                del self._processing_threads[job_id]

    def _create_initial_import_queue_entry(self, filename: str, user_id: int, job_id: str) -> int:
        """Create an initial ImportQueue entry for async processing."""
        try:
            with transaction.atomic():
                # Get user
                user = User.objects.get(id=user_id)

                # Create import queue entry with empty geofeatures during processing
                import_queue = ImportQueue.objects.create(
                    raw_kml='{"type": "FeatureCollection", "features": []}',  # Empty GeoJSON
                    original_filename=filename,
                    user=user,
                    geofeatures=[]  # Empty array during processing
                )

                return import_queue.id

        except Exception as e:
            logger.error(f"Failed to create initial import queue entry for job {job_id}: {str(e)}")
            logger.error(f"Import queue creation error traceback: {traceback.format_exc()}")
            raise

    def _update_import_queue_entry(self, geojson_data: Dict[str, Any],
                                   processing_log: RealTimeImportLog, filename: str,
                                   user_id: int, job_id: str, geojson_str: str, geojson_size_mb: float) -> int:
        """Update an existing ImportQueue entry with processed data."""
        # Get the import queue entry
        job = self.status_tracker.get_job(job_id)
        if not job or not job.import_queue_id:
            raise Exception("No import queue ID found for job")

        try:
            import_queue = ImportQueue.objects.get(id=job.import_queue_id)
        except ImportQueue.DoesNotExist:
            # ImportQueue was deleted (likely by user deletion), stop processing
            logger.info(f"ImportQueue {job.import_queue_id} was deleted during processing, stopping for job {job_id}")
            return job.import_queue_id  # Return the ID even though we can't update it

        try:
            with transaction.atomic():

                # Create normalized content hash for duplicate detection
                # This ensures KML/KMZ files with same content get same hash
                content_hash = hash_normalized_geojson(geojson_data)

                # Process features using the processor's already processed features
                features = geojson_data.get('features', [])

                processing_log.add(f"Processing {len(features)} features from uploaded file", "AsyncProcessor", DatabaseLogLevel.INFO)
                # Features are already processed by the processor, so we use them directly
                processed_features = features

                # Log feature type breakdown
                feature_types = {}
                for feature in processed_features:
                    geom_type = feature.get('geometry', {}).get('type', 'Unknown')
                    feature_types[geom_type] = feature_types.get(geom_type, 0) + 1

                type_summary = ', '.join([f"{count} {ftype}" for ftype, count in feature_types.items()])
                processing_log.add(f"Feature breakdown: {type_summary}", "AsyncProcessor", DatabaseLogLevel.INFO)
                processing_log.add(f"Successfully processed {len(processed_features)} features", "AsyncProcessor", DatabaseLogLevel.INFO)
                processing_log.add("Preparing to save processed data to database", "AsyncProcessor", DatabaseLogLevel.INFO)

                # Compute the geojson_hash for duplicate detection (based on processed features)
                geojson_for_hash = {
                    'type': 'FeatureCollection',
                    'features': processed_features
                }
                geojson_hash = hash_normalized_geojson(geojson_for_hash)

                # Perform duplicate detection against existing features
                processing_log.add("Starting duplicate detection against existing feature store", "AsyncProcessor", DatabaseLogLevel.INFO)

                # Import the duplicate detection functions
                from data.views.import_item import find_coordinate_duplicates, strip_duplicate_features

                # First, check for internal duplicates within the file
                processing_log.add("Checking for internal duplicates within the uploaded file", "AsyncProcessor", DatabaseLogLevel.INFO)
                unique_internal_features, internal_duplicate_count, internal_duplicate_log = strip_duplicate_features(processed_features)
                processing_log.extend(internal_duplicate_log)

                # Then check for coordinate duplicates against existing features
                processing_log.add("Checking for coordinate duplicates against existing features in your library", "AsyncProcessor", DatabaseLogLevel.INFO)
                duplicate_detection_start = time.time()
                unique_features, duplicate_features, duplicate_log = find_coordinate_duplicates(unique_internal_features, user_id)
                duplicate_detection_duration = time.time() - duplicate_detection_start
                processing_log.extend(duplicate_log)
                processing_log.add_timing("Duplicate detection", duplicate_detection_duration, "AsyncProcessor")

                # Log summary of duplicate detection results
                total_duplicates = internal_duplicate_count + len(duplicate_features)
                processing_log.add(f"Duplicate detection completed: {internal_duplicate_count} internal duplicates, {len(duplicate_features)} existing duplicates", "AsyncProcessor", DatabaseLogLevel.INFO)

                # Use the original processed_features (not unique_features) to preserve all features
                # The duplicate_features list contains the duplicate information we need
                processing_log.add(f"Total duplicate features found: {total_duplicates}", "AsyncProcessor", DatabaseLogLevel.INFO)

                # Save the features to the database
                processing_log.add(f"Saving {len(processed_features)} features to database ({geojson_size_mb:.2f} MB)", "AsyncProcessor", DatabaseLogLevel.INFO)

                import_queue.raw_kml = geojson_str
                import_queue.geojson_hash = geojson_hash
                import_queue.geofeatures = processed_features
                import_queue.duplicate_features = duplicate_features  # Store duplicate information
                import_queue.save()

                processing_log.add("Import queue entry updated successfully", "AsyncProcessor", DatabaseLogLevel.INFO)

                # Note: No need to call importlog_to_db since RealTimeImportLog writes to DB immediately

                return import_queue.id

        except Exception as e:
            logger.error(f"Failed to update import queue entry for job {job_id}: {str(e)}")
            logger.error(f"Import queue update error traceback: {traceback.format_exc()}")
            raise

    def cancel_processing(self, job_id: str) -> bool:
        """Cancel a processing job."""
        if self.status_tracker.cancel_job(job_id):
            # Note: We can't actually stop the thread, but we mark it as cancelled
            # The thread will check the status and exit gracefully
            logger.info(f"Cancelled processing for job {job_id}")
            return True
        return False

    def get_processing_stats(self) -> Dict[str, Any]:
        """Get statistics about current processing."""
        return {
            'active_threads': len(self._processing_threads),
            'thread_job_ids': list(self._processing_threads.keys()),
            'status_tracker_stats': self.status_tracker.get_stats()
        }

    def _broadcast_item_added(self, user_id: int, import_queue_id: int):
        """Broadcast WebSocket event when a new item is added."""
        from channels.layers import get_channel_layer
        from asgiref.sync import async_to_sync
        
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"realtime_{user_id}",
                {
                    'type': 'import_queue_item_added',
                    'data': {'id': import_queue_id}
                }
            )

    def _broadcast_status_updated(self, user_id: int, import_queue_id: int, status: str, progress: float, message: str):
        """Broadcast WebSocket event when item status is updated."""
        from channels.layers import get_channel_layer
        from asgiref.sync import async_to_sync
        
        channel_layer = get_channel_layer()
        if channel_layer:
            async_to_sync(channel_layer.group_send)(
                f"realtime_{user_id}",
                {
                    'type': 'import_queue_status_updated',
                    'data': {
                        'id': import_queue_id,
                        'status': status,
                        'progress': progress,
                        'message': message
                    }
                }
            )


# Global instance for the application
async_processor = AsyncFileProcessor(status_tracker)
