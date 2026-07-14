"""
GPX file processor for the unified import pipeline.
Handles GPX-specific conversion logic.
"""

from typing import Dict, Any

from .base.processor import BaseProcessor
from ...logging.console import get_tagged_logger

_logger = get_tagged_logger('GPXPROCESSOR')


class GPXProcessor(BaseProcessor):
    """
    Processor for GPX files.
    Handles GPX-specific conversion logic.
    No namespace removal needed unlike KML files.
    """

    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Convert GPX file to GeoJSON using geo_lib.togeojson (in-process Python port).
        
        Returns:
            GeoJSON data as dictionary
        """
        # Decode content using shared helper
        content = self._decode_content()

        # Convert in-process via geo_lib.togeojson
        return self._convert_to_geojson(content, 'GPX')
