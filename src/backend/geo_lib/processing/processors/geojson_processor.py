"""
GeoJSON file processor for the unified import pipeline.
Handles GeoJSON-specific parsing and validation.
"""
import json
from typing import Dict, Any

from .base_processor import BaseProcessor
from ..logging import DatabaseLogLevel
from ...logging.console import get_tagged_logger
from ...validation.geometry_validation import GeometryValidationError

_logger = get_tagged_logger('GEOJSONPROCESSOR')


class GeoJSONProcessor(BaseProcessor):
    """
    Processor for GeoJSON files.
    GeoJSON files are already in the target format, so we just need to parse and validate.
    Note: GeoJSON uploads are blocked at the upload endpoint, but processing is allowed
    for internal sources (e.g., CalTopo imports).
    """

    def validate(self) -> tuple[bool, str] | tuple[bool, None]:
        """
        Skip file validation for GeoJSON.
        GeoJSON uploads are blocked at the upload endpoint level.
        JSON structure validation happens in convert_to_geojson().
        """
        self.import_log.add("Skipping file validation for GeoJSON (validated during JSON parsing)", "GeoJSONProcessor", DatabaseLogLevel.INFO)
        return True, None

    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Parse and validate GeoJSON file.
        GeoJSON is already in the target format, so we just parse the JSON.
        
        Returns:
            GeoJSON data as dictionary
        """
        # Decode content
        content = self._decode_content()
        
        # Parse JSON
        try:
            geojson_data = json.loads(content)
        except json.JSONDecodeError as e:
            error_msg = f"Invalid JSON format: {str(e)}"
            _logger.error(error_msg)
            self.import_log.add(error_msg, "GeoJSONProcessor", DatabaseLogLevel.ERROR)
            raise GeometryValidationError(error_msg)
        
        # Validate basic GeoJSON structure
        if not isinstance(geojson_data, dict):
            error_msg = "GeoJSON must be a JSON object"
            _logger.error(error_msg)
            self.import_log.add(error_msg, "GeoJSONProcessor", DatabaseLogLevel.ERROR)
            raise GeometryValidationError(error_msg)
        
        # Check for Feature or FeatureCollection
        geojson_type = geojson_data.get('type')
        if geojson_type == 'Feature':
            # Convert single Feature to FeatureCollection for consistency
            geojson_data = {
                'type': 'FeatureCollection',
                'features': [geojson_data]
            }
        elif geojson_type == 'FeatureCollection':
            # Validate features array
            if 'features' not in geojson_data:
                error_msg = "FeatureCollection must have a 'features' array"
                _logger.error(error_msg)
                self.import_log.add(error_msg, "GeoJSONProcessor", DatabaseLogLevel.ERROR)
                raise GeometryValidationError(error_msg)
            
            if not isinstance(geojson_data['features'], list):
                error_msg = "FeatureCollection 'features' must be an array"
                _logger.error(error_msg)
                self.import_log.add(error_msg, "GeoJSONProcessor", DatabaseLogLevel.ERROR)
                raise GeometryValidationError(error_msg)
        else:
            error_msg = f"GeoJSON type must be 'Feature' or 'FeatureCollection', got '{geojson_type}'"
            _logger.error(error_msg)
            self.import_log.add(error_msg, "GeoJSONProcessor", DatabaseLogLevel.ERROR)
            raise GeometryValidationError(error_msg)
        
        self.import_log.add(f"Parsed GeoJSON with {len(geojson_data.get('features', []))} features", "GeoJSONProcessor", DatabaseLogLevel.INFO)
        
        return geojson_data

