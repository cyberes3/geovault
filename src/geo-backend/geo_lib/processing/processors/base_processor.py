"""
Base processor class for unified file import pipeline.
Defines common processing logic that all file type processors inherit.
"""

import json
import os
import subprocess
import tempfile
import time
from abc import ABC, abstractmethod
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, Any, Tuple, Union, List, Optional

from geo_lib.processing.file_types import FileType, detect_file_type
from geo_lib.processing.geo_processor import (
    extract_track_created_date,
    geojson_property_generation,
    split_complex_geometries
)
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.processing.status_tracker import ProcessingStatusTracker, ProcessingStatus
from geo_lib.processing.tagging import generate_auto_tags
from geo_lib.security.file_validation import SecureFileValidator
from geo_lib.logging.console import get_import_logger
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature

logger = get_import_logger()


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
            from django.core.files.uploadedfile import SimpleUploadedFile

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
            logger.error(f"Validation error: {str(e)}")
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

    def _process_single_feature(self, feature: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], ImportLog, int, bool]:
        """
        Process a single feature, including splitting complex geometries and geocoding.
        This is a worker function designed to be called in parallel.
        
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
                
            if split_feature['geometry']['type'] in ['Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon']:
                try:
                    # Generate properties with appropriate styling based on file type and feature geometry
                    split_feature['properties'] = geojson_property_generation(split_feature)

                    # Extract track created date from first point timestamp (for KML/GPX tracks)
                    # Only set if created date is not already present
                    if 'created' not in split_feature['properties'] or not split_feature['properties']['created']:
                        track_timestamp = extract_track_created_date(split_feature)
                        if track_timestamp:
                            split_feature['properties']['created'] = track_timestamp

                    # Skip tag generation in minimal processing mode
                    if not self.minimal_processing:
                        # Generate all auto tags (type, import-year, import-month, geocoding) using generate_auto_tags()
                        # Check for cancellation before tag generation
                        if self._is_cancelled():
                            break
                        
                        try:
                            geometry_type = split_feature['geometry']['type'].lower()
                            
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
                            
                            if feature_class:
                                # Create feature instance for tag generation
                                feature_instance = feature_class(**split_feature)
                                
                                # Check for cancellation before generating tags
                                if self._is_cancelled():
                                    break
                                
                                # Generate all auto tags (includes type, import-year, import-month, source-file, and geocoding)
                                auto_tags = generate_auto_tags(feature_instance, feature_log, filename=self.filename)
                                
                                # Check for cancellation after tag generation
                                if self._is_cancelled():
                                    break
                                
                                # Merge auto tags with existing tags, avoiding duplicates
                                existing_tags = split_feature['properties'].get('tags', [])
                                if not isinstance(existing_tags, list):
                                    existing_tags = []
                                # Combine existing tags with auto tags, avoiding duplicates
                                all_tags = list(existing_tags) + [tag for tag in auto_tags if tag not in existing_tags]
                                split_feature['properties']['tags'] = all_tags
                        except Exception as tag_error:
                            # Log error but don't fail the feature processing
                            feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
                            feature_log.add(
                                f"Tag generation failed for feature '{feature_name}': {str(tag_error)}",
                                "Tag Generation",
                                DatabaseLogLevel.WARNING
                            )
                            logger.warning(f"Tag generation failed for feature '{feature_name}': {tag_error}")
                    # In minimal processing mode, preserve existing tags from file if any, but don't generate new ones

                    # Check for cancellation before finalizing feature
                    if self._is_cancelled():
                        break
                    
                    # Convert to our property format
                    from geo_lib.types.geojson import GeojsonRawProperty
                    split_feature['properties'] = GeojsonRawProperty(**split_feature['properties']).model_dump(mode='json')
                    
                    # Generate and set feature ID if not already present
                    if 'id' not in split_feature.get('properties', {}):
                        from geo_lib.feature_id import generate_feature_hash
                        feature_id = generate_feature_hash(split_feature)
                        split_feature['properties']['id'] = feature_id
                    
                    processed_features.append(split_feature)
                except Exception as e:
                    feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
                    feature_log.add(f"Failed to process feature '{feature_name}', skipping", 'Feature Processing', DatabaseLogLevel.WARNING)
                    logger.error(f"Feature processing error for '{feature_name}': {str(e)}")
                    skipped_count += 1
            else:
                feature_log.add(f'Skipping unsupported geometry type: {split_feature["geometry"]["type"]}', 'Feature Processing', DatabaseLogLevel.WARNING)
                skipped_count += 1

        return processed_features, feature_log, skipped_count, was_split

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

    def process_features(self, geojson_data: Dict[str, Any]) -> Tuple[list, ImportLog]:
        """
        Process features from GeoJSON data.
        Common feature processing logic used by all file types.
        Uses parallel processing via ThreadPoolExecutor for improved performance.
        Supports cancellation checking during processing.
        
        Args:
            geojson_data: GeoJSON data dictionary
            
        Returns:
            Tuple of (processed_features, processing_log)
        """
        features = geojson_data.get('features', [])
        file_type = self.detect_file_type()

        # Process features using the logic that was in process_togeojson_features
        processed_features = []
        feature_log = ImportLog()

        skipped_count = 0
        geometry_collection_count = 0

        feature_log.add(f"Processing {len(features)} raw features from file", "Feature Processing", DatabaseLogLevel.INFO)

        # Check for cancellation before starting
        if self._is_cancelled():
            feature_log.add("Processing cancelled before feature processing started", "Feature Processing", DatabaseLogLevel.WARNING)
            return processed_features, feature_log

        # Count features that will be geocoded (points and lines only)
        from django.conf import settings
        geocoding_enabled = getattr(settings, 'REVERSE_GEOCODING_ENABLED', True)
        geocoding_count = 0
        if geocoding_enabled:
            for feature in features:
                split_features = split_complex_geometries(feature)
                for split_feature in split_features:
                    geometry_type = split_feature.get('geometry', {}).get('type', '').lower()
                    if geometry_type in ['point', 'linestring', 'multilinestring']:
                        geocoding_count += 1
            if geocoding_count > 0:
                feature_log.add(f"Geocoding {geocoding_count} feature(s)", "Geocoding", DatabaseLogLevel.INFO)

        # Get number of threads from settings
        num_threads = getattr(settings, 'IMPORT_PROCESSING_THREADS', 4)

        # Process features in parallel using ThreadPoolExecutor
        # Use submit() instead of map() to allow cancellation checking between tasks
        if len(features) > 0:
            self._executor = ThreadPoolExecutor(max_workers=num_threads)
            executor_shutdown_called = False
            try:
                # Submit all tasks
                future_to_feature = {
                    self._executor.submit(self._process_single_feature, feature): feature 
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
                                # MultiPoint and MultiPolygon should not appear (they should be GeometryCollection)
                                # If they do, split_complex_geometries() will assert/error
                            completed_count += 1
                        except Exception as e:
                            feature = future_to_feature[future]
                            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                            logger.error(f"Error processing feature '{feature_name}': {str(e)}")
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

            # Step 4: Process features
            feature_processing_start = time.time()
            self.processed_features, processing_log = self.process_features(self.geojson_data)
            feature_processing_duration = time.time() - feature_processing_start
            # Extend processing log first, then add timing so logs appear in correct order
            self.import_log.extend(processing_log)
            self.import_log.add_timing("Feature processing", feature_processing_duration, "Processing")

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
                logger.error(f"Processing error: {str(e)}")
            raise

    def _calculate_timeout(self) -> int:
        """
        Calculate timeout based on file size.
        
        Returns:
            Timeout in seconds
        """
        from django.conf import settings
        
        file_size = len(self.file_data) if isinstance(self.file_data, bytes) else len(self.file_data.encode('utf-8'))
        file_size_mb = file_size / (1024 * 1024)

        # Base timeout plus additional timeout per MB for large files
        timeout_base = getattr(settings, 'PROCESSING_TIMEOUT_BASE_SECONDS', 30)
        timeout_per_mb = getattr(settings, 'PROCESSING_TIMEOUT_PER_MB_SECONDS', 2)
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

            # Use the JavaScript converter with file path
            # Note: Timing is handled by the base processor's process() method
            self.import_log.add(f"Converting {file_type_name} file to GeoJSON format", "File Conversion", DatabaseLogLevel.INFO)
            result = subprocess.run(
                ['node', togeojson_path, file_path],
                capture_output=True,
                text=True,
                timeout=self._calculate_timeout()
            )

            if result.returncode != 0:
                raise Exception(f"{file_type_name} file conversion failed")

            geojson_data = json.loads(result.stdout)
            return geojson_data

        except subprocess.TimeoutExpired:
            self.import_log.add(f"{file_type_name} conversion timed out after {self._calculate_timeout()}s", "File Conversion", DatabaseLogLevel.ERROR)
            raise Exception(f"{file_type_name} file conversion timed out")
        except json.JSONDecodeError as e:
            self.import_log.add(f"{file_type_name} conversion produced invalid output - file may be corrupted", "File Conversion", DatabaseLogLevel.ERROR)
            raise Exception(f"{file_type_name} file conversion failed")
        except Exception as e:
            self.import_log.add(f"{file_type_name} conversion failed: {type(e).__name__}", "File Conversion", DatabaseLogLevel.ERROR)
            logger.error(f"{file_type_name} conversion error: {str(e)}")
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
