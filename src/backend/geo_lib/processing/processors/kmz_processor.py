"""
KMZ file processor for the unified import pipeline.
Inherits from KMLProcessor since KMZ is just a zipped KML file.
"""

from typing import Dict, Any

from geo_lib.processing.icons.icon_manager import process_geojson_icons
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.security.SecureFileValidator import secure_kmz_to_kml
from .kml_processor import KMLProcessor
from ...logging.console import get_tagged_logger

_logger = get_tagged_logger('KMZPROCESSOR')


class KMZProcessor(KMLProcessor):
    """
    Processor for KMZ files.
    Inherits from KMLProcessor since KMZ is just a zipped KML file.
    The only difference is extracting the KML from the ZIP archive first.
    """

    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Convert KMZ file to GeoJSON by extracting KML first, then using parent's conversion logic.
        Also processes embedded icons if icon processing is enabled.
        
        Returns:
            GeoJSON data as dictionary
            
        Raises:
            Exception: If KMZ extraction or conversion fails
        """
        kmz_data = self.file_data if isinstance(self.file_data, bytes) else self.file_data.encode('utf-8')  # ensure bytes

        try:
            kml_content = secure_kmz_to_kml(kmz_data)
        except Exception as e:
            error_msg = f"Failed to extract KML from KMZ: {str(e)}"
            self.import_log.add(error_msg, "KMZ Extraction", DatabaseLogLevel.ERROR)
            _logger.info(error_msg)
            raise Exception(error_msg)

        # Convert the extracted KML using parent's logic (text mode for KML)
        geojson_data = self._convert_to_geojson(kml_content, '.kml', 'KML', is_text=True)

        # Process icons in GeoJSON (still need original KMZ data for icon extraction)
        geojson_data = process_geojson_icons(
            geojson_data,
            file_type='kmz',
            import_log=self.import_log,
            file_data=kmz_data
        )

        return geojson_data
