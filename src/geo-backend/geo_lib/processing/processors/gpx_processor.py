"""
GPX file processor for the unified import pipeline.
Handles GPX-specific conversion logic.
"""

from typing import Dict, Any

from .base_processor import BaseProcessor

logger = __import__('logging').getLogger(__name__)


class GPXProcessor(BaseProcessor):
    """
    Processor for GPX files.
    Handles GPX-specific conversion logic.
    No namespace removal needed unlike KML files.
    """

    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Convert GPX file to GeoJSON using JavaScript togeojson library.
        
        Returns:
            GeoJSON data as dictionary
        """
        # Decode content using shared helper
        content = self._decode_content()

        # Convert using shared temp file helper
        return self._convert_to_geojson(content, '.gpx', 'GPX', is_text=True)
