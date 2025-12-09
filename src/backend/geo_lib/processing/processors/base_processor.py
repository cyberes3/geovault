"""
Base processor class for unified file import pipeline.
Defines common processing logic that all file type processors inherit.
"""

import json
import os
import subprocess
import tempfile
import time
import traceback
from abc import ABC, abstractmethod
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, Any, Tuple, Union, List, Optional

from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.file_types import FileType, detect_file_type
from geo_lib.processing.geo_processor import (
    extract_track_created_date,
    geojson_property_generation,
    split_complex_geometries
)
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.processing.elevation_service import fill_missing_elevations
from geo_lib.processing.status_tracker import ProcessingStatusTracker, ProcessingStatus
from geo_lib.processing.tagging import generate_auto_tags
from geo_lib.security.file_validation import SecureFileValidator
from geo_lib.validation.geometry_validation import validate_coordinates_values, GeometryValidationError
from geo_lib.logging.console import get_job_logger
from website.settings_utils import get_required_setting
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
from geo_lib.const_strings import CONST_INTERNAL_TAGS, is_protected_tag, filter_protected_tags, prepare_user_tags
from geo_lib.types.geojson import GeojsonRawProperty
from django.conf import settings
from django.core.files.uploadedfile import SimpleUploadedFile

logger = get_job_logger()


class BaseProcessor(ABC):
    """
    Abstract base class for file processors.
    Defines the common processing pipeline that all file types follow.
    """

    def __init__(self, file_data: Union[bytes, str], filename: str = "", 
                 job_id: Optional[str] = None, 
                 status_tracker: Optional[ProcessingStatusTracker] = None,
                 minimal_processing: bool = False):
        """
        Initialize the processor.
        
        Args:
            file_data: File content as bytes or string
            filename: Original filename for context
            job_id: Optional job ID for cancellation checking
            status_tracker: Optional status tracker for cancellation checking
            minimal_processing: If True, skip tag generation and other expensive operations
        """
        self.file_data = file_data
        self.filename = filename
        self.import_log = ImportLog()
        self.file_type = None
        self.geojson_data = None
        self.processed_features = []
        self.job_id = job_id
        self.status_tracker = status_tracker
        self.minimal_processing = minimal_processing
        self._executor = None  # Store executor reference for proper shutdown

    def detect_file_type(self) -> FileType:
        """
        Detect the file type based on content and filename.
        Can be overridden by subclasses for specific detection logic.
        
        Returns:
            FileType enum value
        """
        if self.file_type is None:
            self.file_type = detect_file_type(self.file_data, self.filename)
        return self.file_type

    def validate(self) -> bool:
        """
        Validate file security and format.
        Uses the existing SecureFileValidator.
        
        Returns:
            True if validation passes, False otherwise
        """
        try:
            # Create a mock uploaded file for validation

            # Determine content type based on file type
            file_type = self.detect_file_type()
            if file_type == FileType.KMZ:
                content_type = 'application/zip'
            else:
                content_type = 'text/xml'

            uploaded_file = SimpleUploadedFile(
                name=self.filename,
                content=self.file_data,
                content_type=content_type
            )

            # Validate file with timing
            validation_start = time.time()
            validator = SecureFileValidator()
            is_valid, validation_message = validator.validate_file(uploaded_file)
            validation_duration = time.time() - validation_start
            self.import_log.add_timing("File validation", validation_duration, "Processing")

            if not is_valid:
                self.import_log.add(f"File validation failed: {validation_message}", "Validation", DatabaseLogLevel.ERROR)
                return False

            self.import_log.add("File validation passed successfully", "Validation", DatabaseLogLevel.INFO)
            return True

        except Exception as e:
            self.import_log.add(f"File validation error: {str(e)}", "Validation", DatabaseLogLevel.ERROR)
            logger.error(f"Validation error: {traceback.format_exc()}")
            return False

    @abstractmethod
    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Convert file to GeoJSON format.
        Must be implemented by subclasses.
        
        Returns:
            GeoJSON data as dictionary
        """
        raise NotImplemented

    def _is_cancelled(self) -> bool:
        """
        Check if the current job has been cancelled.
        
        Returns:
            True if job is cancelled, False otherwise
        """
        if self.job_id and self.status_tracker:
            job = self.status_tracker.get_job(self.job_id)
            if job and job.status == ProcessingStatus.CANCELLED:
                return True
        return False

    def step_4_split_and_validate_features(self, geojson_data: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], ImportLog]:
        """
        Step 4: Split complex geometries and validate coordinates.
        Does NOT generate tags - that happens after elevation filling in step 6.
        
        Args:
            geojson_data: GeoJSON data dictionary
            
        Returns:
            Tuple of (processed_features, processing_log)
        """
        features = geojson_data.get('features', [])
        processed_features = []
        feature_log = ImportLog()
        skipped_count = 0
        geometry_collection_count = 0

        feature_log.add(f"Processing {len(features)} raw features from file", "Feature Processing", DatabaseLogLevel.INFO)

        # Check for cancellation before starting
        if self._is_cancelled():
            feature_log.add("Processing cancelled before feature processing started", "Feature Processing", DatabaseLogLevel.WARNING)
            return processed_features, feature_log

        # Get number of threads from settings
        num_threads = get_required_setting('IMPORT_PROCESSING_THREADS')

        # Process features in parallel using ThreadPoolExecutor
        # Use submit() instead of map() to allow cancellation checking between tasks
        if len(features) > 0:
            self._executor = ThreadPoolExecutor(max_workers=num_threads)
            executor_shutdown_called = False
            try:
                # Submit all tasks
                future_to_feature = {
                    self._executor.submit(self._step_4_process_single_feature, feature): feature 
                    for feature in features
                }

                # Collect results as they complete, checking for cancellation
                completed_count = 0
                cancelled = False
                for future in as_completed(future_to_feature):
                    # Check for cancellation before processing each result
                    if self._is_cancelled():
                        if not cancelled:
                            feature_log.add(f"Processing cancelled after {completed_count} features", "Feature Processing", DatabaseLogLevel.WARNING)
                            cancelled = True
                            # Cancel remaining futures (they'll finish but we won't process results)
                            for remaining_future in future_to_feature:
                                if not remaining_future.done():
                                    remaining_future.cancel()
                            # Shutdown executor without waiting for remaining tasks
                            self._executor.shutdown(wait=False)
                            executor_shutdown_called = True
                            # Break immediately - don't process any more results
                            break

                    # Only process results if not cancelled
                    if not cancelled:
                        try:
                            result_features, result_log, result_skipped, was_split = future.result()
                            processed_features.extend(result_features)
                            feature_log.extend(result_log)
                            skipped_count += result_skipped
                            
                            # Track what type of split occurred by checking the original feature
                            if was_split:
                                original_feature = future_to_feature[future]
                                original_geom_type = original_feature.get('geometry', {}).get('type', '')
                                if original_geom_type == 'GeometryCollection':
                                    geometry_collection_count += 1
                            completed_count += 1
                        except Exception as e:
                            feature = future_to_feature[future]
                            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                            logger.error(f"Error processing feature '{feature_name}': {traceback.format_exc()}")
                            feature_log.add(f"Error processing feature '{feature_name}': {str(e)}", "Feature Processing", DatabaseLogLevel.ERROR)
                            skipped_count += 1
                            completed_count += 1
                    else:
                        # Cancellation detected - skip processing this result
                        completed_count += 1
            finally:
                # Ensure executor is always properly shut down
                if not executor_shutdown_called:
                    # If not already shut down, wait for all tasks to complete
                    self._executor.shutdown(wait=True)
                self._executor = None  # Clear reference

        # Log summary
        if self._is_cancelled():
            feature_log.add(f"Processing was cancelled. Processed {len(processed_features)} features before cancellation", "Feature Processing", DatabaseLogLevel.WARNING)
        else:
            if geometry_collection_count > 0:
                feature_log.add(f"Split {geometry_collection_count} geometry collection(s) into individual features", "Feature Processing", DatabaseLogLevel.INFO)

            if skipped_count > 0:
                feature_log.add(f"Skipped {skipped_count} features (invalid geometry or unsupported type)", "Feature Processing", DatabaseLogLevel.INFO)

            feature_log.add(f"Successfully processed {len(processed_features)} features", "Feature Processing", DatabaseLogLevel.INFO)

        return processed_features, feature_log

    def step_6_tag_features(self, processed_features: List[Dict[str, Any]]) -> ImportLog:
        """
        Step 6: Generate tags for already-processed (split and validated) features.
        Tags include: type, import date, source file, elevation, and reverse geocoding.
        
        Args:
            processed_features: List of features that have been split and validated
            
        Returns:
            ImportLog with tagging information
        """
        feature_log = ImportLog()
        
        if not processed_features or self.minimal_processing:
            return feature_log
        
        # Log start of tagging process
        feature_log.add(f"Starting feature tagging for {len(processed_features)} feature(s)", "Feature Tagging", DatabaseLogLevel.INFO)
        
        # Count features that will be reverse geocoded (points and lines only)
        geocoding_enabled = get_required_setting('REVERSE_GEOCODING_ENABLED')
        geocoding_count = 0
        if geocoding_enabled:
            for feature in processed_features:
                geometry_type = feature.get('geometry', {}).get('type', '').lower()
                if geometry_type in ['point', 'linestring', 'multilinestring']:
                    geocoding_count += 1
            if geocoding_count > 0:
                feature_log.add(f"Reverse geocoding {geocoding_count} feature(s)", "Reverse Geocoding", DatabaseLogLevel.INFO)

        # Get number of threads from settings
        num_threads = get_required_setting('IMPORT_PROCESSING_THREADS')

        # Process features in parallel using ThreadPoolExecutor
        if len(processed_features) > 0:
            self._executor = ThreadPoolExecutor(max_workers=num_threads)
            executor_shutdown_called = False
            try:
                # Submit all tasks
                future_to_feature = {
                    self._executor.submit(self._step_6_process_single_feature, feature): feature 
                    for feature in processed_features
                }

                # Collect results as they complete, checking for cancellation
                completed_count = 0
                cancelled = False
                for future in as_completed(future_to_feature):
                    # Check for cancellation before processing each result
                    if self._is_cancelled():
                        if not cancelled:
                            feature_log.add(f"Tagging cancelled after {completed_count} features", "Feature Tagging", DatabaseLogLevel.WARNING)
                            cancelled = True
                            # Cancel remaining futures
                            for remaining_future in future_to_feature:
                                if not remaining_future.done():
                                    remaining_future.cancel()
                            # Shutdown executor without waiting for remaining tasks
                            self._executor.shutdown(wait=False)
                            executor_shutdown_called = True
                            break

                    # Only process results if not cancelled
                    if not cancelled:
                        try:
                            result_log = future.result()
                            feature_log.extend(result_log)
                            completed_count += 1
                        except Exception as e:
                            feature = future_to_feature[future]
                            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                            logger.error(f"Error tagging feature '{feature_name}': {traceback.format_exc()}")
                            feature_log.add(f"Error tagging feature '{feature_name}': {str(e)}", "Feature Tagging", DatabaseLogLevel.ERROR)
                            completed_count += 1
                    else:
                        completed_count += 1
            finally:
                # Ensure executor is always properly shut down
                if not executor_shutdown_called:
                    self._executor.shutdown(wait=True)
                self._executor = None  # Clear reference

        # Log summary of reverse geocoding results if geocoding was enabled
        if geocoding_enabled and geocoding_count > 0:
            # Count successful and failed geocoding operations from the log messages
            geocoding_success_count = 0
            geocoding_failure_count = 0
            for log_msg in feature_log.get():
                if log_msg.source == "Reverse Geocoding":
                    # Count successes (has location tags)
                    if "Successfully reverse geocoded feature" in log_msg.msg:
                        geocoding_success_count += 1
                    # Count failures (no data / API errors)
                    elif "returned no data" in log_msg.msg or "failed" in log_msg.msg.lower():
                        geocoding_failure_count += 1
            
            # Calculate actual success count
            actual_success = geocoding_count - geocoding_failure_count
            
            # Log summary
            if geocoding_failure_count > 0:
                feature_log.add(
                    f"Reverse geocoding: {actual_success} succeeded, {geocoding_failure_count} failed (API errors)",
                    "Reverse Geocoding",
                    DatabaseLogLevel.WARNING
                )
            else:
                feature_log.add(
                    f"Successfully reverse geocoded all {geocoding_count} feature(s)",
                    "Reverse Geocoding",
                    DatabaseLogLevel.INFO
                )

        return feature_log

    def _step_4_process_single_feature(self, feature: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], ImportLog, int, bool]:
        """
        Worker for step 4: Split and validate a single feature.
        Does NOT generate tags - that happens in step 6.
        
        Args:
            feature: Single feature dictionary from GeoJSON
            
        Returns:
            Tuple of (processed_features_list, feature_log, skipped_count, was_split)
        """
        # Check for cancellation at the very start
        if self._is_cancelled():
            return [], ImportLog(), 0, False

        feature_log = ImportLog()
        processed_features = []
        skipped_count = 0
        was_split = False

        # Split complex geometries (GeometryCollection, MultiPoint, MultiPolygon) into separate features
        split_features = split_complex_geometries(feature)

        # Check if this feature was split
        if len(split_features) > 1:
            was_split = True

        # Skip features with no valid geometry
        if not split_features:
            skipped_count += 1
            return processed_features, feature_log, skipped_count, was_split

        for split_feature in split_features:
            # Check for cancellation before processing each split feature
            if self._is_cancelled():
                break
            
            # Validate coordinates
            try:
                validate_coordinates_values(split_feature['geometry'])
            except GeometryValidationError as e:
                feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
                feature_log.add(
                    f"Skipping feature '{feature_name}' due to invalid coordinates: {str(e)}",
                    "Feature Processing",
                    DatabaseLogLevel.WARNING
                )
                skipped_count += 1
                continue
                
            if split_feature['geometry']['type'] not in ['Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon']:
                feature_log.add(f'Skipping unsupported geometry type: {split_feature["geometry"]["type"]}', 'Feature Processing', DatabaseLogLevel.WARNING)
                skipped_count += 1
                continue

            try:
                # Extract track created date BEFORE normalization (to ensure coordinateProperties is available)
                # Only extract if created date is not already present
                original_properties = split_feature.get('properties', {})
                if not original_properties.get('created'):
                    track_timestamp = extract_track_created_date(split_feature)
                    # Set created date in properties BEFORE normalization so it's preserved
                    if track_timestamp:
                        split_feature['properties']['created'] = track_timestamp

                # First, normalize raw togeojson output (converts feature_tags -> tags, etc.)
                split_feature['properties'] = GeojsonRawProperty(**split_feature['properties']).model_dump(mode='json', exclude_none=True, by_alias=True)

                # Then validate and normalize properties with styling (uses PropertiesModel)
                split_feature['properties'] = geojson_property_generation(split_feature)

                # Finally, generate the geojson hash after all the normalization is complete
                split_feature['properties']['geojson_hash'] = generate_geojson_hash(split_feature)

                # Check for cancellation before finalizing feature
                if self._is_cancelled():
                    break

                processed_features.append(split_feature)
            except Exception:
                feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
                feature_log.add(f"Failed to process feature '{feature_name}', skipping", 'Feature Processing', DatabaseLogLevel.WARNING)
                logger.error(f"Feature processing error for '{feature_name}': {traceback.format_exc()}")
                skipped_count += 1

        return processed_features, feature_log, skipped_count, was_split

    def _step_6_process_single_feature(self, feature: Dict[str, Any]) -> ImportLog:
        """
        Worker for step 6: Generate tags for a single feature.
        
        Args:
            feature: Single feature dictionary that has been split and validated
            
        Returns:
            ImportLog with tagging information
        """
        feature_log = ImportLog()
        
        # Check for cancellation at the very start
        if self._is_cancelled():
            return feature_log

        try:
            geometry_type = feature['geometry']['type'].lower()

            # Determine the appropriate feature class
            feature_class = None
            if geometry_type in ['point', 'multipoint']:
                feature_class = PointFeature
            elif geometry_type == 'linestring':
                feature_class = LineStringFeature
            elif geometry_type == 'multilinestring':
                feature_class = MultiLineStringFeature
            elif geometry_type in ['polygon', 'multipolygon']:
                feature_class = PolygonFeature
            else:
                # Unsupported geometry type, skip tagging
                return feature_log
            assert feature_class

            # Create feature instance for tag generation
            feature_instance = feature_class(**feature)

            # Check for cancellation before generating tags
            if self._is_cancelled():
                return feature_log

            # Generate all auto tags (includes type, import-year, import-month, source-file, elevation, and geocoding)
            auto_tags = generate_auto_tags(feature_instance, feature_log, filename=self.filename)

            # Check for cancellation after tag generation
            if self._is_cancelled():
                return feature_log

            # Separate system tags from user tags
            existing_tags = feature.get('properties', {}).get('tags', [])
            if not isinstance(existing_tags, list):
                existing_tags = []

            # Strip system tags from existing tags (defensive - in case user added them)
            user_tags = filter_protected_tags(existing_tags, CONST_INTERNAL_TAGS)

            # Prepare user tags (lowercase and deduplicate)
            user_tags = prepare_user_tags(user_tags)

            # Store system tags separately
            feature['properties']['system_tags'] = auto_tags
            # Store user tags (filtered to remove any system tags)
            feature['properties']['tags'] = user_tags
        except Exception as tag_error:
            # Log error but don't fail the feature processing
            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
            feature_log.add(
                f"Tag generation failed for feature '{feature_name}': {str(tag_error)}",
                "Tag Generation",
                DatabaseLogLevel.WARNING
            )
            logger.warning(f"Tag generation failed for feature '{feature_name}': {traceback.format_exc()}")

        return feature_log

    def process(self) -> Tuple[Dict[str, Any], ImportLog]:
        """
        Main processing pipeline orchestrator.
        Calls all processing steps in order.
        Checks for cancellation at each step.
        
        Returns:
            Tuple of (geojson_data, import_log)
        """
        try:
            # Step 1: Detect file type
            detection_start = time.time()
            file_type = self.detect_file_type()
            detection_duration = time.time() - detection_start
            self.import_log.add_timing("File type detection", detection_duration, "Processing")

            # Check for cancellation
            if self._is_cancelled():
                self.import_log.add("Processing cancelled during file type detection", "Processing", DatabaseLogLevel.WARNING)
                return {'type': 'FeatureCollection', 'features': []}, self.import_log

            # Step 2: Validate file
            if not self.validate():
                raise Exception("File validation failed")

            # Check for cancellation
            if self._is_cancelled():
                self.import_log.add("Processing cancelled during file validation", "Processing", DatabaseLogLevel.WARNING)
                return {'type': 'FeatureCollection', 'features': []}, self.import_log

            # Step 3: Convert to GeoJSON
            conversion_start = time.time()
            self.geojson_data = self.convert_to_geojson()
            conversion_duration = time.time() - conversion_start
            self.import_log.add_timing(f"{file_type.value.upper()} conversion", conversion_duration, "File Conversion")

            # Check for cancellation
            if self._is_cancelled():
                self.import_log.add("Processing cancelled during GeoJSON conversion", "Processing", DatabaseLogLevel.WARNING)
                return {'type': 'FeatureCollection', 'features': []}, self.import_log

            # Step 4: Split and validate features (without tagging)
            feature_processing_start = time.time()
            self.processed_features, processing_log = self.step_4_split_and_validate_features(self.geojson_data)
            feature_processing_duration = time.time() - feature_processing_start
            # Extend processing log first, then add timing so logs appear in correct order
            self.import_log.extend(processing_log)
            self.import_log.add_timing("Feature splitting and validation", feature_processing_duration, "Processing")

            # Check for cancellation
            if self._is_cancelled():
                self.import_log.add("Processing cancelled during feature splitting", "Processing", DatabaseLogLevel.WARNING)
                return {'type': 'FeatureCollection', 'features': []}, self.import_log

            # Step 5: Fill missing elevation data
            # Done BEFORE tagging (step 6) so elevation tags use real data, not 0.0 placeholders
            # Done AFTER splitting/validation (step 4) so coordinates are valid and geometries are simple
            elevation_start = time.time()
            try:
                if get_required_setting('ELEVATION_API_ENABLED') and self.processed_features:
                    temp_geojson = {'type': 'FeatureCollection', 'features': self.processed_features}
                    fill_missing_elevations(temp_geojson, self.import_log)
                    elevation_duration = time.time() - elevation_start
                    self.import_log.add_timing("Elevation data filling", elevation_duration, "Processing")
            except Exception as e:
                self.import_log.add(f"Elevation data filling failed: {str(e)}", "Elevation Service", DatabaseLogLevel.ERROR)
                logger.error(f"Elevation data filling error traceback: {traceback.format_exc()}")
            
            # Check for cancellation
            if self._is_cancelled():
                self.import_log.add("Processing cancelled during elevation data filling", "Processing", DatabaseLogLevel.WARNING)
                return {'type': 'FeatureCollection', 'features': []}, self.import_log

            # Step 6: Generate tags (elevation tags now use real data from step 5)
            # Tags include: geometry type, import date, source file, elevation, and reverse geocoding
            # Note: Reverse geocoding queries OpenStreetMap for location-based tags
            tagging_start = time.time()
            self.import_log.add("Generating feature tags (including reverse geocoding)...", "Processing", DatabaseLogLevel.INFO)
            tagging_log = self.step_6_tag_features(self.processed_features)
            tagging_duration = time.time() - tagging_start
            self.import_log.extend(tagging_log)
            self.import_log.add_timing("Feature tagging", tagging_duration, "Processing")
            
            # Check for cancellation
            if self._is_cancelled():
                self.import_log.add("Processing cancelled during feature tagging", "Processing", DatabaseLogLevel.WARNING)
                return {'type': 'FeatureCollection', 'features': []}, self.import_log

            # Create final GeoJSON structure
            final_geojson = {
                'type': 'FeatureCollection',
                'features': self.processed_features
            }

            return final_geojson, self.import_log

        except Exception as e:
            # Don't log error if job was cancelled
            if not self._is_cancelled():
                self.import_log.add(f"Processing failed: {str(e)}", "Processing", DatabaseLogLevel.ERROR)
                logger.error(f"Processing error: {traceback.format_exc()}")
            raise

    def _calculate_timeout(self) -> int:
        """
        Calculate timeout based on file size.
        
        Returns:
            Timeout in seconds
        """
        file_size = len(self.file_data) if isinstance(self.file_data, bytes) else len(self.file_data.encode('utf-8'))
        file_size_mb = file_size / (1024 * 1024)

        # Base timeout plus additional timeout per MB for large files
        timeout_base = get_required_setting('PROCESSING_TIMEOUT_BASE_SECONDS')
        timeout_per_mb = get_required_setting('PROCESSING_TIMEOUT_PER_MB_SECONDS')
        timeout_seconds = max(timeout_base, int(timeout_base + (file_size_mb * timeout_per_mb)))

        self.import_log.add(f'Calculated timeout: {timeout_seconds}s for {file_size_mb:.1f}MB file', 'Processing', DatabaseLogLevel.DEBUG)
        return timeout_seconds

    def _decode_content(self) -> str:
        """
        Decode file data to string if needed.
        Common helper for processors that need string content.
        
        Returns:
            File content as string
        """
        if isinstance(self.file_data, str):
            return self.file_data
        else:
            return self.file_data.decode('utf-8')

    def _convert_to_geojson(self, content: Union[str, bytes], suffix: str, file_type_name: str, is_text: bool = True) -> Dict[str, Any]:
        """
        Convert file to GeoJSON using a temporary file.
        Handles temp file creation, conversion, and cleanup.
        
        Args:
            content: File content as string or bytes
            suffix: File suffix (e.g., '.gpx', '.kml', '.kmz')
            file_type_name: Name of file type for logging (e.g., "GPX", "KML", "KMZ")
            is_text: Whether to write in text mode (True) or binary mode (False)
            
        Returns:
            GeoJSON data as dictionary
        """
        # Create temporary file with appropriate mode
        if is_text:
            with tempfile.NamedTemporaryFile(mode='w', suffix=suffix, delete=False, encoding='utf-8') as temp_file:
                temp_file.write(content)
                temp_file_path = temp_file.name
        else:
            with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as temp_file:
                temp_file.write(content)
                temp_file_path = temp_file.name

        try:
            # Use the shared Node.js conversion logic
            geojson_data = self._convert_via_nodejs(temp_file_path, file_type_name)
            return geojson_data
        finally:
            # Clean up temporary file
            os.unlink(temp_file_path)

    def _convert_via_nodejs(self, file_path: str, file_type_name: str) -> Dict[str, Any]:
        """
        Convert file to GeoJSON using JavaScript togeojson library.
        Common conversion logic shared between KML, KMZ, and GPX processors.
        
        Args:
            file_path: Path to the file to convert
            file_type_name: Name of file type for logging (e.g., "KML", "KMZ", "GPX")
            
        Returns:
            GeoJSON data as dictionary
        """
        try:
            # Get the path to the togeojson converter
            current_dir = os.path.dirname(os.path.abspath(__file__))
            togeojson_path = os.path.join(current_dir, '..', 'togeojson', 'index.js')
            togeojson_path = os.path.normpath(togeojson_path)  # Normalize path

            # Verify the converter script exists
            if not os.path.exists(togeojson_path):
                error_msg = f"Node.js converter script not found at {togeojson_path}"
                logger.error(error_msg)
                self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
                raise FileNotFoundError(error_msg)

            # Get file info for logging
            file_size = os.path.getsize(file_path) if os.path.exists(file_path) else 0
            file_size_mb = file_size / (1024 * 1024) if file_size > 0 else 0
            filename = self.filename or os.path.basename(file_path)
            
            # Verify the input file exists
            if not os.path.exists(file_path):
                error_msg = f"Input file does not exist: {file_path}"
                logger.error(error_msg)
                self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
                raise FileNotFoundError(error_msg)

            # Use the JavaScript converter with file path
            # Note: Timing is handled by the base processor's process() method
            self.import_log.add(f"Converting {file_type_name} file to GeoJSON format", "File Conversion", DatabaseLogLevel.INFO)
            logger.info(f"Starting {file_type_name} conversion for file '{filename}' ({file_size_mb:.2f} MB)")
            
            result = subprocess.run(
                ['node', togeojson_path, file_path],
                capture_output=True,
                text=True,
                timeout=self._calculate_timeout()
            )

            if result.returncode != 0:
                # Capture detailed error information
                stderr_output = result.stderr.strip() if result.stderr else "No error output"
                stdout_output = result.stdout.strip() if result.stdout else "No output"
                
                error_msg = f"{file_type_name} file conversion failed"
                detailed_error = f"Node.js conversion failed for '{filename}' (return code: {result.returncode})"
                
                if stderr_output:
                    detailed_error += f"\nNode.js stderr: {stderr_output}"
                if stdout_output and not stdout_output.startswith('{'):
                    # Only log stdout if it's not valid JSON (which would be the error message)
                    detailed_error += f"\nNode.js stdout: {stdout_output}"
                
                logger.error(detailed_error)
                self.import_log.add(f"{error_msg}: {stderr_output if stderr_output else 'Unknown error'}", "File Conversion", DatabaseLogLevel.ERROR)
                raise Exception(f"{error_msg}: {stderr_output if stderr_output else 'Unknown error'}")

            # Validate that we got valid output
            if not result.stdout or not result.stdout.strip():
                error_msg = f"{file_type_name} conversion produced no output"
                logger.error(f"{error_msg} for file '{filename}'")
                self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
                raise Exception(error_msg)

            try:
                geojson_data = json.loads(result.stdout)
                logger.info(f"Successfully converted {file_type_name} file '{filename}' to GeoJSON")
                return geojson_data
            except json.JSONDecodeError as json_err:
                # Log the actual output that failed to parse
                output_preview = result.stdout[:500] if len(result.stdout) > 500 else result.stdout
                error_msg = f"{file_type_name} conversion produced invalid JSON output"
                detailed_error = f"{error_msg} for file '{filename}': {str(json_err)}\nOutput preview: {output_preview}"
                logger.error(detailed_error)
                self.import_log.add(f"{error_msg} - file may be corrupted or invalid", "File Conversion", DatabaseLogLevel.ERROR)
                raise Exception(f"{error_msg}: {str(json_err)}")

        except subprocess.TimeoutExpired as e:
            timeout_seconds = self._calculate_timeout()
            error_msg = f"{file_type_name} conversion timed out after {timeout_seconds}s"
            logger.error(f"{error_msg} for file '{filename}' ({file_size_mb:.2f} MB)")
            self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
            raise Exception(f"{file_type_name} file conversion timed out")
        except FileNotFoundError:
            error_msg = f"Node.js not found - cannot convert {file_type_name} file"
            logger.error(f"{error_msg} for file '{filename}'. Is Node.js installed?")
            self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
            raise Exception(error_msg)
        except Exception as e:
            # Re-raise if it's already been handled above
            if "conversion" in str(e).lower() or "timeout" in str(e).lower():
                raise
            
            logger.error(f"{error_msg} for file '{filename}': {traceback.format_exc()}")
            self.import_log.add(f"{file_type_name} conversion failed: {str(e)}", "File Conversion", DatabaseLogLevel.ERROR)
            raise

    def get_file_metadata(self) -> Dict[str, Any]:
        """
        Get file metadata for logging and debugging.
        
        Returns:
            Dictionary with file metadata
        """
        file_size = len(self.file_data) if isinstance(self.file_data, bytes) else len(self.file_data.encode('utf-8'))
        return {
            'filename': self.filename,
            'file_type': self.detect_file_type().value,
            'file_size_bytes': file_size,
            'file_size_mb': file_size / (1024 * 1024),
            'feature_count': len(self.processed_features) if self.processed_features else 0
        }