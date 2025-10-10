"""
Asynchronous file processor for handling large file uploads in the background.
Processes files without blocking the main request thread.
"""

import threading
import time
import traceback
import logging
from typing import Dict, Any

from django.contrib.auth.models import User
from django.db import transaction

from geo_lib.processing.status_tracker import ProcessingStatusTracker, ProcessingStatus, status_tracker
from geo_lib.processing.geo_processor import geo_to_geojson
from geo_lib.processing.logging import ImportLog, RealTimeImportLog, DatabaseLogLevel
from geo_lib.security.file_validation import SecureFileValidator, SecurityError, FileValidationError
from data.models import ImportQueue
from geo_lib.logging.database import importlog_to_db

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
        except Exception as e:
            logger.error(f"Failed to create initial import queue entry for job {job_id}: {str(e)}")
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
        # Get the import queue ID for logging
        job = self.status_tracker.get_job(job_id)
        import_queue_id = job.import_queue_id if job else None
        
        # Get the UUID from the ImportQueue for logging
        log_uuid = None
        if import_queue_id:
            try:
                import_queue = ImportQueue.objects.get(id=import_queue_id)
                log_uuid = str(import_queue.log_id) if import_queue.log_id else None
            except ImportQueue.DoesNotExist:
                pass
        
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
            realtime_log.add("Starting file validation and processing", "AsyncProcessor", DatabaseLogLevel.INFO)
            
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
            realtime_log.add(f"Created validation file object for {filename}", "AsyncProcessor", DatabaseLogLevel.DEBUG)
            
            # Validate file with timing
            validator = SecureFileValidator()
            realtime_log.add("Running comprehensive file validation", "AsyncProcessor", DatabaseLogLevel.INFO)
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
                return
            
            realtime_log.add("File validation passed successfully", "AsyncProcessor", DatabaseLogLevel.INFO)
            
            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING, 
                "File validation passed, starting conversion...", 30.0
            )
            realtime_log.add("File validation passed, starting conversion", "AsyncProcessor", DatabaseLogLevel.INFO)
            
            # Process file to GeoJSON
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING, 
                "Converting to GeoJSON format...", 50.0
            )
            realtime_log.add("Starting GeoJSON conversion", "AsyncProcessor", DatabaseLogLevel.INFO)
            
            # Get file size for logging
            file_size_mb = len(file_data) / (1024 * 1024)
            realtime_log.add(f"Processing {file_size_mb:.1f}MB file", "AsyncProcessor", DatabaseLogLevel.INFO)
            
            # Convert to GeoJSON with timing
            conversion_start = time.time()
            geojson_data, processing_log = geo_to_geojson(file_data, filename)
            conversion_duration = time.time() - conversion_start
            realtime_log.add_timing("GeoJSON conversion", conversion_duration, "AsyncProcessor")
            
            # Add processing log messages to real-time log
            realtime_log.extend(processing_log)
            
            # Update progress
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING, 
                "GeoJSON conversion complete, processing features...", 70.0
            )
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
            
            # Update existing import queue entry with timing
            feature_processing_start = time.time()
            import_queue_id = self._update_import_queue_entry(
                geojson_data, realtime_log, filename, user_id, job_id
            )
            feature_processing_duration = time.time() - feature_processing_start
            realtime_log.add_timing("Feature processing and database update", feature_processing_duration, "AsyncProcessor")
            
            # Mark as completed
            overall_duration = time.time() - overall_start_time
            realtime_log.add_timing("Total file processing", overall_duration, "AsyncProcessor")
            
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED, 
                "File processing completed successfully!", 100.0
            )
            realtime_log.add("File processing completed successfully!", "AsyncProcessor", DatabaseLogLevel.INFO)
            
            # Set result data
            self.status_tracker.set_job_result(
                job_id, 
                {'geojson_data': geojson_data, 'processing_log': realtime_log},
                import_queue_id
            )
            
            logger.info(f"Successfully completed processing for job {job_id}")
            realtime_log.add(f"Processing job {job_id} completed successfully", "AsyncProcessor", DatabaseLogLevel.INFO)
            
        except (SecurityError, FileValidationError) as e:
            error_msg = f"Security validation failed: {str(e)}"
            logger.error(f"Security error in job {job_id}: {error_msg}")
            realtime_log.add(error_msg, "AsyncProcessor", DatabaseLogLevel.ERROR)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED, 
                error_msg, error_message=error_msg
            )
            
        except Exception as e:
            error_msg = f"Unexpected error during processing: {str(e)}"
            logger.error(f"Processing error in job {job_id}: {traceback.format_exc()}")
            realtime_log.add(error_msg, "AsyncProcessor", DatabaseLogLevel.ERROR)
            realtime_log.add(f"Full error traceback: {traceback.format_exc()}", "AsyncProcessor", DatabaseLogLevel.DEBUG)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED, 
                error_msg, error_message=error_msg
            )
            
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
                # Use a temporary hash based on job_id to avoid unique constraint violations
                temp_hash = f"processing_{job_id}"
                import_queue = ImportQueue.objects.create(
                    raw_kml='{"type": "FeatureCollection", "features": []}',  # Empty GeoJSON
                    raw_kml_hash=temp_hash,  # Temporary hash during processing
                    original_filename=filename,
                    user=user,
                    geofeatures=[]  # Empty array during processing
                )
                
                return import_queue.id
                
        except Exception as e:
            logger.error(f"Failed to create initial import queue entry for job {job_id}: {str(e)}")
            raise

    def _update_import_queue_entry(self, geojson_data: Dict[str, Any], 
                                 processing_log: RealTimeImportLog, filename: str, 
                                 user_id: int, job_id: str) -> int:
        """Update an existing ImportQueue entry with processed data."""
        try:
            with transaction.atomic():
                # Get the existing import queue entry
                job = self.status_tracker.get_job(job_id)
                if not job or not job.import_queue_id:
                    raise Exception("No import queue ID found for job")
                
                import_queue = ImportQueue.objects.get(id=job.import_queue_id)
                
                # Convert geojson_data to string for storage
                import json
                geojson_str = json.dumps(geojson_data)
                
                # Create content hash
                import hashlib
                content_hash = hashlib.sha256(geojson_str.encode('utf-8')).hexdigest()
                
                # Process features
                from geo_lib.processing.geo_processor import process_togeojson_features, detect_file_type
                
                processing_log.add("Detecting file type for feature processing", "AsyncProcessor", DatabaseLogLevel.DEBUG)
                file_type = detect_file_type(geojson_str, filename)
                features = geojson_data.get('features', [])
                
                processing_log.add(f"Processing {len(features)} features", "AsyncProcessor", DatabaseLogLevel.INFO)
                processed_features, feature_log = process_togeojson_features(features, file_type)
                processing_log.extend(feature_log)
                
                processing_log.add(f"Successfully processed {len(processed_features)} features", "AsyncProcessor", DatabaseLogLevel.INFO)
                
                # Update import queue entry
                processing_log.add("Updating import queue entry with processed data", "AsyncProcessor", DatabaseLogLevel.INFO)
                import_queue.raw_kml = geojson_str
                import_queue.raw_kml_hash = content_hash
                import_queue.geofeatures = processed_features
                import_queue.save()
                
                processing_log.add("Import queue entry updated successfully", "AsyncProcessor", DatabaseLogLevel.INFO)
                
                # Note: No need to call importlog_to_db since RealTimeImportLog writes to DB immediately
                
                return import_queue.id
                
        except Exception as e:
            logger.error(f"Failed to update import queue entry for job {job_id}: {str(e)}")
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


# Global instance for the application
async_processor = AsyncFileProcessor(status_tracker)
