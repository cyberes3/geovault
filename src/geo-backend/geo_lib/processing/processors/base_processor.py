"""
Base processor class for unified file import pipeline.
Defines common processing logic that all file type processors inherit.
"""

import logging
import time
from abc import ABC, abstractmethod
from typing import Dict, Any, Tuple, Union

from geo_lib.processing.file_types import FileType, detect_file_type
from geo_lib.processing.geo_processor import (
    preserve_togeojson_styling,
    split_geometry_collection
)
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.security.file_validation import SecureFileValidator

logger = logging.getLogger(__name__)


class BaseProcessor(ABC):
    """
    Abstract base class for file processors.
    Defines the common processing pipeline that all file types follow.
    """

    def __init__(self, file_data: Union[bytes, str], filename: str = ""):
        """
        Initialize the processor.
        
        Args:
            file_data: File content as bytes or string
            filename: Original filename for context
        """
        self.file_data = file_data
        self.filename = filename
        self.import_log = ImportLog()
        self.file_type = None
        self.geojson_data = None
        self.processed_features = []

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

    def process_features(self, geojson_data: Dict[str, Any]) -> Tuple[list, ImportLog]:
        """
        Process features from GeoJSON data.
        Common feature processing logic used by all file types.
        
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

        for feature in features:
            # Split GeometryCollection into separate features
            split_features = split_geometry_collection(feature)

            # Count geometry collections that were split
            if len(split_features) > 1:
                geometry_collection_count += 1

            # Skip features with no valid geometry
            if not split_features:
                skipped_count += 1
                continue

            for split_feature in split_features:
                if split_feature['geometry']['type'] in ['Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon']:
                    try:
                        # Apply appropriate styling based on file type
                        split_feature['properties'] = preserve_togeojson_styling(split_feature['properties'], file_type)

                        # Convert to our property format
                        from geo_lib.types.geojson import GeojsonRawProperty
                        split_feature['properties'] = GeojsonRawProperty(**split_feature['properties']).model_dump()
                        processed_features.append(split_feature)
                    except Exception as e:
                        feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
                        feature_log.add(f"Failed to process feature '{feature_name}', skipping", 'Feature Processing', DatabaseLogLevel.WARNING)
                        logger.error(f"Feature processing error for '{feature_name}': {str(e)}")
                        skipped_count += 1
                else:
                    feature_log.add(f'Skipping unsupported geometry type: {split_feature["geometry"]["type"]}', 'Feature Processing', DatabaseLogLevel.WARNING)
                    skipped_count += 1

        # Log summary
        if geometry_collection_count > 0:
            feature_log.add(f"Split {geometry_collection_count} geometry collections into individual features", "Feature Processing", DatabaseLogLevel.INFO)

        if skipped_count > 0:
            feature_log.add(f"Skipped {skipped_count} features (invalid geometry or unsupported type)", "Feature Processing", DatabaseLogLevel.INFO)

        feature_log.add(f"Successfully processed {len(processed_features)} features", "Feature Processing", DatabaseLogLevel.INFO)

        self.import_log.extend(feature_log)
        return processed_features, self.import_log

    def process(self) -> Tuple[Dict[str, Any], ImportLog]:
        """
        Main processing pipeline orchestrator.
        Calls all processing steps in order.
        
        Returns:
            Tuple of (geojson_data, import_log)
        """
        try:
            # Step 1: Detect file type
            detection_start = time.time()
            file_type = self.detect_file_type()
            detection_duration = time.time() - detection_start
            self.import_log.add_timing("File type detection", detection_duration, "Processing")

            # Step 2: Validate file
            if not self.validate():
                raise Exception("File validation failed")

            # Step 3: Convert to GeoJSON
            conversion_start = time.time()
            self.geojson_data = self.convert_to_geojson()
            conversion_duration = time.time() - conversion_start
            self.import_log.add_timing(f"{file_type.value.upper()} conversion", conversion_duration, "File Conversion")

            # Step 4: Process features
            feature_processing_start = time.time()
            self.processed_features, processing_log = self.process_features(self.geojson_data)
            feature_processing_duration = time.time() - feature_processing_start
            self.import_log.add_timing("Feature processing", feature_processing_duration, "Processing")
            self.import_log.extend(processing_log)

            # Create final GeoJSON structure
            final_geojson = {
                'type': 'FeatureCollection',
                'features': self.processed_features
            }

            return final_geojson, self.import_log

        except Exception as e:
            self.import_log.add(f"Processing failed: {str(e)}", "Processing", DatabaseLogLevel.ERROR)
            logger.error(f"Processing error: {str(e)}")
            raise

    def _calculate_timeout(self) -> int:
        """
        Calculate timeout based on file size.
        
        Returns:
            Timeout in seconds
        """
        file_size = len(self.file_data) if isinstance(self.file_data, bytes) else len(self.file_data.encode('utf-8'))
        file_size_mb = file_size / (1024 * 1024)

        # Base timeout of 30 seconds, plus 2 seconds per MB for large files
        timeout_seconds = max(30, int(30 + (file_size_mb * 2)))

        self.import_log.add(f'Calculated timeout: {timeout_seconds}s for {file_size_mb:.1f}MB file', 'Processing', DatabaseLogLevel.DEBUG)
        return timeout_seconds

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
