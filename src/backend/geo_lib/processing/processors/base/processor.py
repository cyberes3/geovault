"""
Base processor class for unified file import pipeline.
Defines common processing logic that all file type processors inherit.

`BaseProcessor` is an orchestrator: it owns per-file state (file_data, job_id,
status_tracker, etc.) and sequences the pipeline steps, while each step's
actual logic lives in a focused, independently-testable sibling module
(`feature_splitting`, `tagging_step`, `duplicate_step`, `track_override`,
`content_decoding`, `conversion_runner`).
"""
import time
import traceback
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Tuple, Union

from django.core.files.uploadedfile import SimpleUploadedFile

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.elevation_service import fill_missing_elevations
from geo_lib.processing.file_types import FileType, detect_file_type
from geo_lib.processing.job_ceiling import calculate_conversion_timeout_seconds, calculate_job_ceiling_seconds
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, ProcessingStatusTracker
from geo_lib.processing.logging import DatabaseLogLevel, ImportLog, RealTimeImportLog
from geo_lib.processing.processors.base import content_decoding, conversion_runner, duplicate_step, feature_splitting, tagging_step, track_override
from geo_lib.processing.utils import inject_feature_hashes
from geo_lib.security.exceptions import FileValidationError
from geo_lib.security.secure_file_validator import validate_file
from geo_lib.utils.feature_utils import build_feature_type_summary
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
                 import_queue_id: Optional[int] = None,
                 realtime_log: Optional[RealTimeImportLog] = None):
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
            realtime_log: Optional real-time log; when set, long-running steps (e.g. elevation)
                write to it so messages appear in the UI as they happen.
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
        self.realtime_log = realtime_log

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
            elif file_type == FileType.GEOJSON:
                content_type = 'application/json'
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
        raise NotImplementedError

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
        Does NOT generate tags - that happens in step 7 (tagging and reverse geocoding).

        Args:
            geojson_data: GeoJSON data dictionary

        Returns:
            Tuple of (processed_features, processing_log)
        """
        features = geojson_data.get('features', [])
        num_threads = get_required_setting('IMPORT_PROCESSING_THREADS')
        return feature_splitting.split_and_validate_features(features, num_threads, self._is_canceled)

    def step_5_fill_elevations(self) -> ImportLog:
        """
        Step 5: Fill missing elevation data for features.
        Uses elevation API to fill in missing elevation values.
        Operates on self.processed_features in-place.
        When self.realtime_log is set, batch progress is written there so messages
        appear in the import log as each batch is processed.

        Returns:
            ImportLog with elevation filling information (empty when using realtime_log)
        """
        step_log = ImportLog()
        # Use realtime log when available so batch messages stream to the UI
        elevation_log = self.realtime_log if self.realtime_log is not None else step_log

        try:
            # Check if elevation API is enabled
            if not get_required_setting('ELEVATION_API_ENABLED'):
                return step_log

            if not self.processed_features:
                return step_log

            # Check for cancellation
            if self._is_canceled():
                elevation_log.add("Processing canceled during elevation data filling", "Processing", DatabaseLogLevel.WARNING)
                return step_log

            # Fill elevations (modifies features in-place)
            temp_geojson = {'type': 'FeatureCollection', 'features': self.processed_features}
            fill_missing_elevations(temp_geojson, elevation_log)

        except Exception as e:
            step_log.add(f"Elevation data filling failed: {str(e)}", "Elevation Service", DatabaseLogLevel.ERROR)
            _logger.error(f"Elevation data filling error traceback: {traceback.format_exc()}")

        return step_log

    def step_7_tag_features(self) -> ImportLog:
        """
        Step 7: Generate all tags for features including reverse geocoding.
        Generates tags for: type, import date, source file, elevation,
        and location-based tags (city, state, country, protected areas, etc.)
        Uses batch processing with coordinate deduplication.

        Returns:
            ImportLog with tagging and reverse_geocoding information
        """
        if not self.processed_features or self.minimal_processing:
            return ImportLog()

        # Normalize file_data for tag generators (strip BOM if present)
        _logger.debug(
            f"[TAGGING] About to normalize file_data for tagging. "
            f"Filename: {self.filename}, "
            f"file_data type: {type(self.file_data)}, "
            f"file_data size: {len(self.file_data) if self.file_data else 'None'}, "
            f"file_type: {self.file_type}"
        )
        normalized_file_data = self._normalize_file_data_for_tagging()
        _logger.debug(
            f"[TAGGING] Normalized file_data result. "
            f"Type: {type(normalized_file_data)}, "
            f"Size: {len(normalized_file_data) if normalized_file_data else 'None'}, "
            f"Preview: {normalized_file_data[:200] if normalized_file_data and len(normalized_file_data) > 0 else 'None'}"
        )

        return tagging_step.tag_features(self.processed_features, self.filename, normalized_file_data, self._is_canceled)

    def apply_track_name_override(self, geojson_data: Dict[str, Any]) -> ImportLog:
        """
        Apply user setting to overwrite single track name with filename if enabled.

        Args:
            geojson_data: GeoJSON data to potentially modify (modified in-place)

        Returns:
            ImportLog with any relevant messages
        """
        return track_override.apply_track_name_override(geojson_data, self.filename, self.user_id, self.job_id)

    def detect_duplicates(self, processed_features: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], ImportLog]:
        """
        Detect duplicate features (internal, feature store, and cross-queue).

        Args:
            processed_features: List of processed features to check for duplicates

        Returns:
            Tuple of (duplicate_features, detection_log)
        """
        # Skip duplicate detection if minimal processing or no user_id
        if self.minimal_processing or not self.user_id:
            return [], ImportLog()

        return duplicate_step.detect_duplicates(processed_features, self.user_id, self.import_queue_id, self._is_canceled)

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

    def _file_size_bytes(self) -> int:
        """Size of the in-memory file data, decoding to UTF-8 bytes if it's still a str."""
        return len(self.file_data) if isinstance(self.file_data, bytes) else len(self.file_data.encode('utf-8'))

    def _calculate_timeout(self) -> int:
        """
        Calculate timeout based on file size.

        Returns:
            Timeout in seconds
        """
        file_size = self._file_size_bytes()
        timeout_seconds = calculate_conversion_timeout_seconds(file_size)

        file_size_mb = file_size / (1024 * 1024)
        self.import_log.add(f'Calculated timeout: {timeout_seconds}s for {file_size_mb:.1f}MB file', 'Processing', DatabaseLogLevel.DEBUG)
        return timeout_seconds

    def calculate_job_ceiling_seconds(self) -> int:
        """
        Defense-in-depth ceiling for the *entire* job, not just the conversion step.

        Scaled off the same file-size formula as `_calculate_timeout()` but with a larger
        multiplier, so it backstops every pipeline stage (splitting, elevation, tagging,
        DB write) rather than just conversion. Callers (`ProcessJob._check_job_timeout`)
        compare this against total elapsed wall-clock time for the job.
        """
        return calculate_job_ceiling_seconds(self._file_size_bytes())

    def _decode_content(self) -> str:
        """
        Decode file data to string if needed.
        Common helper for processors that need string content.
        Automatically strips UTF-8 BOM if present.

        Returns:
            File content as string (BOM stripped)
        """
        return content_decoding.decode_content(self.file_data)

    def _normalize_file_data_for_tagging(self) -> Optional[str]:
        """
        Normalize file_data for tag generators by stripping BOM if present.
        Returns file_data as string (BOM stripped) for consistent handling.

        Returns:
            File content as string (BOM stripped), or None if decoding fails or file_data is None
        """
        return content_decoding.normalize_file_data_for_tagging(self.file_data, self.filename, self.file_type)

    def _convert_to_geojson(self, content: str, file_type_name: str) -> Dict[str, Any]:
        """
        Convert KML/GPX content to GeoJSON in-process via geo_lib.togeojson.

        `content` must already be fully prepared (decoded, namespace-stripped for KML) by
        the caller -- this method just parses and converts it. `file_type_name` (e.g. "KML",
        "GPX", "KMZ") is used only for log/error messages; `togeojson()` auto-detects KML vs
        GPX from the parsed root element, so this one method serves all three processors with
        no format branching.

        The conversion call itself runs in a single-use thread bounded by `_calculate_timeout()`
        -- see `conversion_runner.convert_xml_to_geojson()` for why.
        """
        timeout_seconds = self._calculate_timeout()
        return conversion_runner.convert_xml_to_geojson(content, file_type_name, timeout_seconds, self.filename, self.import_log)

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
