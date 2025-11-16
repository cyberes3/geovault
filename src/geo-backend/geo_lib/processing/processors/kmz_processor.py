"""
KMZ file processor for the unified import pipeline.
Inherits from KMLProcessor since KMZ is just a zipped KML file.
"""

import io
import os
import tempfile
import zipfile
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
        # Write KMZ data to temporary file for JavaScript converter
        with tempfile.NamedTemporaryFile(suffix='.kmz', delete=False) as temp_file:
            temp_file.write(self.file_data)
            temp_file_path = temp_file.name

        try:
            # Use the parent's shared Node.js conversion logic
            geojson_data = self._convert_via_nodejs(temp_file_path, "KMZ")
            
            # Extract KML content from KMZ for icon processing
            kml_content = None
            try:
                with zipfile.ZipFile(io.BytesIO(self.file_data), 'r') as zip_file:
                    # Find the first .kml file
                    kml_entry = None
                    for entry in zip_file.namelist():
                        if entry.lower().endswith('.kml'):
                            kml_entry = entry
                            break
                    if kml_entry:
                        kml_content = zip_file.read(kml_entry).decode('utf-8')
            except Exception as e:
                logger.warning(f"Failed to extract KML from KMZ for icon processing: {str(e)}")
            
            # Process icons in GeoJSON
            geojson_data = process_geojson_icons(
                geojson_data,
                file_type='kmz',
                file_data=self.file_data if isinstance(self.file_data, bytes) else None,
                kml_content=kml_content
            )
            
            return geojson_data
        finally:
            # Clean up temporary file
            os.unlink(temp_file_path)
