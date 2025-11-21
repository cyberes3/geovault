"""
KMZ file processor for the unified import pipeline.
Inherits from KMLProcessor since KMZ is just a zipped KML file.
"""

from typing import Dict, Any

from geo_lib.processing.icon_manager import process_geojson_icons
from .kml_processor import KMLProcessor

logger = __import__('logging').getLogger(__name__)


class KMZProcessor(KMLProcessor):
    """
    Processor for KMZ files.
    Inherits from KMLProcessor since KMZ is just a zipped KML file.
    The only difference is extracting the KML from the ZIP archive first.
    """

    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Convert KMZ file to GeoJSON by writing to temp file and using parent's conversion logic.
        Also processes embedded icons if icon processing is enabled.
        
        Returns:
            GeoJSON data as dictionary
        """
        # Ensure file_data is bytes for KMZ
        kmz_data = self.file_data if isinstance(self.file_data, bytes) else self.file_data.encode('utf-8')

        # Convert using shared temp file helper (binary mode for KMZ)
        geojson_data = self._convert_to_geojson(kmz_data, '.kmz', 'KMZ', is_text=False)

        # Process icons in GeoJSON
        geojson_data = process_geojson_icons(
            geojson_data,
            file_type='kmz',
            file_data=kmz_data
        )

        return geojson_data
