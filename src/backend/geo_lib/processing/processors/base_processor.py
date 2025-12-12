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

from django.core.files.uploadedfile import SimpleUploadedFile

from api.models import ImportQueue, UserSettings
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.duplicate_detection.duplicate_detection import remove_internal_duplicates, find_duplicates_for_source
from geo_lib.processing.duplicate_detection.models import split_duplicates_by_match_type
from geo_lib.processing.elevation_service import fill_missing_elevations
from geo_lib.processing.file_types import FileType, detect_file_type
from geo_lib.processing.geo import (
    extract_track_created_date,
    geojson_property_generation,
    split_complex_geometries
)
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatusTracker, ProcessingStatus
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.processing.utils import inject_feature_hashes
from geo_lib.security.SecureFileValidator import validate_file
from geo_lib.security.exceptions import FileValidationError
from geo_lib.tags.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, prepare_user_tags
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
from geo_lib.types.geojson import GeojsonRawProperty
from geo_lib.utils.feature_utils import build_feature_type_summary
from geo_lib.validation.geometry_validation import validate_coordinates_values, GeometryValidationError
from website.settings_utils import get_required_setting

_logger = get_tagged_logger('BASEPROCESSOR')


class BaseProcessor(ABC):
    """
    Abstract base class for file processors.
    Defines the common processing pipeline that all file types follow.
    """

    def __init__(self, file_data: Union[bytes, str], filename: str = "",
                 job_id: Optional[str] = None,
                 status_tracker: Optional[ProcessingStatusTracker] = None,
                 minimal_processing: bool = False,
                 user_id: Optional[int] = None,
                 import_queue_id: Optional[int] = None):
        """
        Initialize the processor.
        
        Args:
            file_data: File content as bytes or string
            filename: Original filename for context
            job_id: Optional job ID for cancellation checking
            status_tracker: Optional status tracker for cancellation checking
            minimal_processing: If True, skip tag generation and other expensive operations
            user_id: Optional user ID for database operations
            import_queue_id: Optional import queue ID for database operations
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
        self.user_id = user_id
        self.import_queue_id = import_queue_id
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

    def validate(self) -> tuple[bool, str] | tuple[bool, None]:
        """
        Validate file security and format.
        Uses the existing validate_file function.
        
        Returns:
            Tuple of (is_valid, error_message)
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
            is_valid, validation_message = validate_file(uploaded_file)
            validation_duration = time.time() - validation_start
            self.import_log.add_timing("File validation", validation_duration, "Processing")

            if not is_valid:
                self.import_log.add(f"File validation failed: {validation_message}", "Validation", DatabaseLogLevel.ERROR)
                return False, validation_message

            self.import_log.add("File validation passed successfully", "Validation", DatabaseLogLevel.INFO)
            return True, None

        except Exception as e:
            error_msg = str(e)
            self.import_log.add(f"File validation error: {error_msg}", "Validation", DatabaseLogLevel.ERROR)
            _logger.error(f"Validation error: {traceback.format_exc()}")
            return False, error_msg

    @abstractmethod
    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Convert file to GeoJSON format.
        Must be implemented by subclasses.
        
        Returns:
            GeoJSON data as dictionary
        """
        raise NotImplemented

    def _is_canceled(self) -> bool:
        """
        Check if the current job has been canceled.
        
        Returns:
            True if job is canceled, False otherwise
        """
        if self.job_id and self.status_tracker:
            job = self.status_tracker.get_job(self.job_id)
            if job and job.status == ProcessingStatus.CANCELED:
                return True
        return False

    def step_3_convert_to_geojson(self) -> ImportLog:
        """
        Step 3: Convert file to GeoJSON format.
        Calls the subclass's convert_to_geojson() method and stores result in self.geojson_data.
        
        Returns:
            ImportLog with conversion information
        """
        step_log = ImportLog()

        try:
            # Check for cancellation
            if self._is_canceled():
                step_log.add("Processing canceled during GeoJSON conversion", "Processing", DatabaseLogLevel.WARNING)
                return step_log

            # Perform conversion
            self.geojson_data = self.convert_to_geojson()

            if not self.geojson_data or 'features' not in self.geojson_data:
                error_msg = "Conversion returned invalid GeoJSON data"
                step_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
                raise Exception(error_msg)
            
            # Check if file has any features
            if len(self.geojson_data.get('features', [])) == 0:
                error_msg = "File contains no geographic features (placemarks, waypoints, or tracks)"
                step_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
                raise FileValidationError(error_msg)

        except Exception as e:
            if not self._is_canceled():
                step_log.add(f"GeoJSON conversion failed: {str(e)}", "File Conversion", DatabaseLogLevel.ERROR)
                _logger.error(f"GeoJSON conversion error: {traceback.format_exc()}")
            raise

        return step_log

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
        if self._is_canceled():
            feature_log.add("Processing canceled before feature processing started", "Feature Processing", DatabaseLogLevel.WARNING)
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
                canceled = False
                for future in as_completed(future_to_feature):
                    # Check for cancellation before processing each result
                    if self._is_canceled():
                        if not canceled:
                            feature_log.add(f"Processing canceled after {completed_count} features", "Feature Processing", DatabaseLogLevel.WARNING)
                            canceled = True
                            # Cancel remaining futures (they'll finish but we won't process results)
                            for remaining_future in future_to_feature:
                                if not remaining_future.done():
                                    remaining_future.cancel()
                            # Shutdown executor without waiting for remaining tasks
                            self._executor.shutdown(wait=False)
                            executor_shutdown_called = True
                            # Break immediately - don't process any more results
                            break

                    # Only process results if not canceled
                    if not canceled:
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
                            _logger.error(f"Error processing feature '{feature_name}': {traceback.format_exc()}")
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
        if self._is_canceled():
            feature_log.add(f"Processing was canceled. Processed {len(processed_features)} features before cancellation", "Feature Processing", DatabaseLogLevel.WARNING)
        else:
            if geometry_collection_count > 0:
                feature_log.add(f"Split {geometry_collection_count} geometry collection(s) into individual features", "Feature Processing", DatabaseLogLevel.INFO)

            if skipped_count > 0:
                feature_log.add(f"Skipped {skipped_count} features (invalid geometry or unsupported type)", "Feature Processing", DatabaseLogLevel.INFO)

            feature_log.add(f"Successfully processed {len(processed_features)} features", "Feature Processing", DatabaseLogLevel.INFO)

        return processed_features, feature_log

    def step_5_fill_elevations(self) -> ImportLog:
        """
        Step 5: Fill missing elevation data for features.
        Uses elevation API to fill in missing elevation values.
        Operates on self.processed_features in-place.
        
        Returns:
            ImportLog with elevation filling information
        """
        step_log = ImportLog()

        try:
            # Check if elevation API is enabled
            if not get_required_setting('ELEVATION_API_ENABLED'):
                return step_log

            if not self.processed_features:
                return step_log

            # Check for cancellation
            if self._is_canceled():
                step_log.add("Processing canceled during elevation data filling", "Processing", DatabaseLogLevel.WARNING)
                return step_log

            # Fill elevations (modifies features in-place)
            temp_geojson = {'type': 'FeatureCollection', 'features': self.processed_features}
            fill_missing_elevations(temp_geojson, step_log)

        except Exception as e:
            step_log.add(f"Elevation data filling failed: {str(e)}", "Elevation Service", DatabaseLogLevel.ERROR)
            _logger.error(f"Elevation data filling error traceback: {traceback.format_exc()}")

        return step_log

    def step_6_tag_features(self) -> ImportLog:
        """
        Step 6: Generate tags for already-processed (split and validated) features.
        Tags include: type, import date, source file, and elevation.
        Does NOT include reverse geocoding - that's in step 7.
        Uses the processed_features stored in self.processed_features.
        
        Returns:
            ImportLog with tagging information
        """
        feature_log = ImportLog()

        if not self.processed_features or self.minimal_processing:
            return feature_log

        # Log start of tagging process
        feature_log.add(f"Starting feature tagging for {len(self.processed_features)} feature(s)", "Feature Tagging", DatabaseLogLevel.INFO)

        # Batch process all features at once (without geocoding)
        try:
            # Check for cancellation before starting
            if self._is_canceled():
                feature_log.add("Processing canceled before feature tagging", "Feature Tagging", DatabaseLogLevel.WARNING)
                return feature_log

            # Create feature instances for all features
            feature_instances = []
            for feature in self.processed_features:
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
                        # Unsupported geometry type, add empty instance to maintain index alignment
                        feature_instances.append(None)
                        continue

                    # Create feature instance
                    feature_instance = feature_class(**feature)
                    feature_instances.append(feature_instance)
                except Exception as e:
                    _logger.warning(f"Failed to create feature instance for tagging: {e}")
                    feature_instances.append(None)

            # Check for cancellation after creating instances
            if self._is_canceled():
                feature_log.add("Processing canceled during feature instance creation", "Feature Tagging", DatabaseLogLevel.WARNING)
                return feature_log

            # Batch generate tags for all features at once (SKIP geocoding)
            from geo_lib.processing.tagging.generate import generate_auto_tags_batch
            all_feature_tags = generate_auto_tags_batch(
                [f for f in feature_instances if f is not None],
                import_log=feature_log,
                filename=self.filename,
                skip_reverse_geocoding=True  # Skip reverse geocoding - done in step 7
            )

            # Check for cancellation after tag generation
            if self._is_canceled():
                feature_log.add("Processing canceled after tag generation", "Feature Tagging", DatabaseLogLevel.WARNING)
                return feature_log

            # Apply tags to features
            tag_index = 0
            for i, feature in enumerate(self.processed_features):
                if feature_instances[i] is None:
                    # Skip features that couldn't be instantiated
                    continue

                try:
                    auto_tags = all_feature_tags[tag_index]
                    tag_index += 1

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
                    feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                    feature_log.add(
                        f"Tag application failed for feature '{feature_name}': {str(tag_error)}",
                        "Tag Generation",
                        DatabaseLogLevel.WARNING
                    )
                    _logger.warning(f"Tag application failed for feature '{feature_name}': {traceback.format_exc()}")

        except Exception as e:
            feature_log.add(
                f"Batch tag generation failed: {str(e)}",
                "Tag Generation",
                DatabaseLogLevel.ERROR
            )
            _logger.error(f"Batch tag generation error: {traceback.format_exc()}")

        return feature_log

    def step_7_reverse_geocode(self) -> ImportLog:
        """
        Step 7: Perform reverse geocoding for features.
        Adds location-based tags (city, state, country, protected areas, etc.)
        Uses batch processing with coordinate deduplication.
        
        Returns:
            ImportLog with geocoding information
        """
        feature_log = ImportLog()

        if not self.processed_features or self.minimal_processing:
            return feature_log

        # Check if geocoding is enabled
        geocoding_enabled = get_required_setting('REVERSE_GEOCODING_ENABLED')
        if not geocoding_enabled:
            return feature_log

        # Count features that will be reverse geocoded (points and lines only)
        geocoding_count = 0
        for feature in self.processed_features:
            geometry_type = feature.get('geometry', {}).get('type', '').lower()
            if geometry_type in ['point', 'linestring', 'multilinestring']:
                geocoding_count += 1

        if geocoding_count == 0:
            return feature_log

        feature_log.add(f"Reverse geocoding {geocoding_count} feature(s) with coordinate deduplication", "Reverse Geocoding", DatabaseLogLevel.INFO)

        try:
            # Check for cancellation before starting
            if self._is_canceled():
                feature_log.add("Processing canceled before reverse geocoding", "Reverse Geocoding", DatabaseLogLevel.WARNING)
                return feature_log

            # Create feature instances for all features
            feature_instances = []
            for feature in self.processed_features:
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
                        feature_instances.append(None)
                        continue

                    # Create feature instance
                    feature_instance = feature_class(**feature)
                    feature_instances.append(feature_instance)
                except Exception as e:
                    _logger.warning(f"Failed to create feature instance for geocoding: {e}")
                    feature_instances.append(None)

            # Check for cancellation after creating instances
            if self._is_canceled():
                feature_log.add("Processing canceled during feature instance creation", "Reverse Geocoding", DatabaseLogLevel.WARNING)
                return feature_log

            # Use the reverse geocoding tag generator directly for batch processing
            from geo_lib.processing.tagging.modules.geocoding import ReverseGeocodingTagGenerator
            reverse_geocoding_gen = ReverseGeocodingTagGenerator()

            # Get valid feature instances for reverse geocoding
            valid_features = [f for f in feature_instances if f is not None]

            if valid_features:
                reverse_geocode_tags = reverse_geocoding_gen.process_batch(valid_features, import_log=feature_log)

                # Check for cancellation after reverse geocoding
                if self._is_canceled():
                    feature_log.add("Processing canceled after reverse geocoding", "Reverse Geocoding", DatabaseLogLevel.WARNING)
                    return feature_log

                # Apply reverse geocoding tags to features
                tag_index = 0
                for i, feature in enumerate(self.processed_features):
                    if feature_instances[i] is None:
                        continue

                    try:
                        if tag_index in reverse_geocode_tags:
                            geo_tags = reverse_geocode_tags[tag_index]
                            # Append reverse geocoding tags to existing system_tags
                            existing_system_tags = feature.get('properties', {}).get('system_tags', [])
                            if not isinstance(existing_system_tags, list):
                                existing_system_tags = []
                            feature['properties']['system_tags'] = existing_system_tags + geo_tags
                        tag_index += 1
                    except Exception as tag_error:
                        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                        feature_log.add(
                            f"Reverse geocoding tag application failed for feature '{feature_name}': {str(tag_error)}",
                            "Reverse Geocoding",
                            DatabaseLogLevel.WARNING
                        )
                        _logger.warning(f"Geocoding tag application failed for feature '{feature_name}': {traceback.format_exc()}")

        except Exception as e:
            feature_log.add(
                f"Reverse geocoding failed: {str(e)}",
                "Reverse Geocoding",
                DatabaseLogLevel.ERROR
            )
            _logger.error(f"Reverse geocoding error: {traceback.format_exc()}")

        return feature_log

    def apply_track_name_override(self, geojson_data: Dict[str, Any]) -> ImportLog:
        """
        Apply user setting to overwrite single track name with filename if enabled.
        
        Args:
            geojson_data: GeoJSON data to potentially modify (modified in-place)
            
        Returns:
            ImportLog with any relevant messages
        """
        step_log = ImportLog()

        if not self.user_id:
            return step_log

        try:
            user_settings_obj = UserSettings.objects.filter(user_id=self.user_id).first()
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
                            filename_without_ext = os.path.splitext(self.filename)[0]
                            # Overwrite the name property
                            properties['name'] = filename_without_ext
                            feature['properties'] = properties
                            _logger.info(f"Overwrote single track name with filename '{filename_without_ext}' for job {self.job_id}")
                            step_log.add(f"Applied track name override: '{filename_without_ext}'", "Track Name Override", DatabaseLogLevel.INFO)
        except Exception as e:
            _logger.error(f"Error applying track name override: {traceback.format_exc()}")
            step_log.add(f"Failed to apply track name override: {str(e)}", "Track Name Override", DatabaseLogLevel.ERROR)

        return step_log

    def detect_duplicates(self, processed_features: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], ImportLog]:
        """
        Detect duplicate features (internal, feature store, and cross-queue).
        
        Args:
            processed_features: List of processed features to check for duplicates
            
        Returns:
            Tuple of (duplicate_features, detection_log)
        """
        step_log = ImportLog()
        duplicate_features = []

        # Skip duplicate detection if minimal processing or no user_id
        if self.minimal_processing or not self.user_id:
            return duplicate_features, step_log

        try:
            # Check for cancellation before duplicate detection
            if self._is_canceled():
                step_log.add("Processing canceled before duplicate detection", "Duplicate Detection", DatabaseLogLevel.WARNING)
                return duplicate_features, step_log

            # Start duplicate detection timing
            duplicate_detection_start = time.time()

            # First, check for internal duplicates within the file
            unique_internal_features, internal_duplicate_count = remove_internal_duplicates(processed_features)

            if internal_duplicate_count > 0:
                step_log.add(f"Found {internal_duplicate_count} internal duplicate(s)", "Duplicate Detection", DatabaseLogLevel.INFO)
            else:
                step_log.add("No internal duplicates found", "Duplicate Detection", DatabaseLogLevel.INFO)

            # Check for cancellation after internal duplicate detection
            if self._is_canceled():
                step_log.add("Processing canceled after internal duplicate detection", "Duplicate Detection", DatabaseLogLevel.WARNING)
                return duplicate_features, step_log

            # Get ImportQueue for exclude parameters
            import_queue = None
            if self.import_queue_id:
                try:
                    import_queue = ImportQueue.objects.get(id=self.import_queue_id)
                except ImportQueue.DoesNotExist:
                    pass

            # PASS 1: Check feature store (hash + geometry, with hash priority)
            remaining_after_fs, feature_store_duplicates, fs_log = find_duplicates_for_source(
                unique_internal_features,
                self.user_id,
                source='feature_store',
                exclude_queue_id=None,
                exclude_timestamp=None
            )

            # Split feature store duplicates into hash and geometry for tracking
            feature_store_hash_duplicates, feature_store_geom_duplicates = split_duplicates_by_match_type(
                feature_store_duplicates
            )

            # PASS 2: Check cross-queue (hash + geometry, with hash priority) on remaining features
            exclude_queue_id = import_queue.id if import_queue else None
            exclude_timestamp = import_queue.timestamp if import_queue else None

            remaining_after_cq, cross_queue_duplicates, cq_log = find_duplicates_for_source(
                remaining_after_fs,
                self.user_id,
                source='cross_queue',
                exclude_queue_id=exclude_queue_id,
                exclude_timestamp=exclude_timestamp
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
            step_log.add(summary, "Duplicate Detection", DatabaseLogLevel.INFO)

            duplicate_detection_duration = time.time() - duplicate_detection_start
            step_log.add(f"Duplicate detection completed ({duplicate_detection_duration:.1f}s)", "Duplicate Detection", DatabaseLogLevel.INFO)

            # Check for cancellation after duplicate detection
            if self._is_canceled():
                step_log.add("Processing canceled after duplicate detection", "Duplicate Detection", DatabaseLogLevel.WARNING)
                return duplicate_features, step_log

        except Exception as e:
            _logger.error(f"Error during duplicate detection: {traceback.format_exc()}")
            step_log.add(f"Duplicate detection failed: {str(e)}", "Duplicate Detection", DatabaseLogLevel.ERROR)

        return duplicate_features, step_log

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

    def finalize_features(self) -> Tuple[Dict[str, Any], ImportLog]:
        """
        Finalize processed features by:
        - Injecting feature hashes
        - Building feature type summary
        - Applying track name override
        - Detecting duplicates
        
        Returns:
            Tuple of (result_dict, finalization_log) where result_dict contains:
            - geojson_data: Final GeoJSON with all features
            - duplicate_features: List of detected duplicates
            - feature_count: Total number of features
            - type_summary: Feature type breakdown
        """
        finalization_log = ImportLog()

        # Build final GeoJSON data from processed features
        geojson_data = {
            'type': 'FeatureCollection',
            'features': self.processed_features
        }

        # Pre-calculate and inject geojson_hash into properties
        inject_feature_hashes(self.processed_features)
        finalization_log.add(f"Injected feature hashes for {len(self.processed_features)} features", "Feature Finalization", DatabaseLogLevel.DEBUG)

        # Log feature type breakdown
        type_summary = build_feature_type_summary(self.processed_features)
        finalization_log.add(f"Feature breakdown: {type_summary}", "Feature Finalization", DatabaseLogLevel.INFO)

        # Apply user setting: overwrite single track name with filename if enabled
        track_override_log = self.apply_track_name_override(geojson_data)
        finalization_log.extend(track_override_log)

        # Detect duplicates
        duplicate_features, duplicate_log = self.detect_duplicates(self.processed_features)
        finalization_log.extend(duplicate_log)

        # Build result dictionary
        result = {
            'geojson_data': geojson_data,
            'duplicate_features': duplicate_features,
            'feature_count': len(self.processed_features),
            'type_summary': type_summary
        }

        return result, finalization_log

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
        if self._is_canceled():
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
            if self._is_canceled():
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
                if self._is_canceled():
                    break

                processed_features.append(split_feature)
            except Exception:
                feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
                feature_log.add(f"Failed to process feature '{feature_name}', skipping", 'Feature Processing', DatabaseLogLevel.WARNING)
                _logger.error(f"Feature processing error for '{feature_name}': {traceback.format_exc()}")
                skipped_count += 1

        return processed_features, feature_log, skipped_count, was_split

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
                _logger.error(error_msg)
                self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
                raise FileNotFoundError(error_msg)

            # Get file info for logging
            file_size = os.path.getsize(file_path) if os.path.exists(file_path) else 0
            file_size_mb = file_size / (1024 * 1024) if file_size > 0 else 0
            filename = self.filename or os.path.basename(file_path)

            # Verify the input file exists
            if not os.path.exists(file_path):
                error_msg = f"Input file does not exist: {file_path}"
                _logger.error(error_msg)
                self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
                raise FileNotFoundError(error_msg)

            # Use the JavaScript converter with file path
            # Note: Timing is handled by the base processor's process() method
            self.import_log.add(f"Converting {file_type_name} file to GeoJSON format", "File Conversion", DatabaseLogLevel.INFO)
            _logger.info(f"Starting {file_type_name} conversion for file '{filename}' ({file_size_mb:.2f} MB)")

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

                _logger.error(detailed_error)
                self.import_log.add(f"{error_msg}: {stderr_output if stderr_output else 'Unknown error'}", "File Conversion", DatabaseLogLevel.ERROR)
                raise Exception(f"{error_msg}: {stderr_output if stderr_output else 'Unknown error'}")

            # Validate that we got valid output
            if not result.stdout or not result.stdout.strip():
                error_msg = f"{file_type_name} conversion produced no output"
                _logger.error(f"{error_msg} for file '{filename}'")
                self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
                raise Exception(error_msg)

            try:
                geojson_data = json.loads(result.stdout)
                _logger.info(f"Successfully converted {file_type_name} file '{filename}' to GeoJSON")
                return geojson_data
            except json.JSONDecodeError as json_err:
                # Log the actual output that failed to parse
                output_preview = result.stdout[:500] if len(result.stdout) > 500 else result.stdout
                error_msg = f"{file_type_name} conversion produced invalid JSON output"
                detailed_error = f"{error_msg} for file '{filename}': {str(json_err)}\nOutput preview: {output_preview}"
                _logger.error(detailed_error)
                self.import_log.add(f"{error_msg} - file may be corrupted or invalid", "File Conversion", DatabaseLogLevel.ERROR)
                raise Exception(f"{error_msg}: {str(json_err)}")

        except subprocess.TimeoutExpired as e:
            timeout_seconds = self._calculate_timeout()
            error_msg = f"{file_type_name} conversion timed out after {timeout_seconds}s"
            _logger.error(f"{error_msg} for file '{filename}' ({file_size_mb:.2f} MB)")
            self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
            raise Exception(f"{file_type_name} file conversion timed out")
        except FileNotFoundError:
            error_msg = f"Node.js not found - cannot convert {file_type_name} file"
            _logger.error(f"{error_msg} for file '{filename}'. Is Node.js installed?")
            self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
            raise Exception(error_msg)
        except Exception as e:
            # Re-raise if it's already been handled above
            if "conversion" in str(e).lower() or "timeout" in str(e).lower():
                raise

            _logger.error(f"{error_msg} for file '{filename}': {traceback.format_exc()}")
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
