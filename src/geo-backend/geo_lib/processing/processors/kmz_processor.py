"""
KMZ file processor for the unified import pipeline.
Inherits from KMLProcessor since KMZ is just a zipped KML file.
"""

import os
import tempfile
from typing import Dict, Any

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
        
        Returns:
            GeoJSON data as dictionary
        """
        # Write KMZ data to temporary file for JavaScript converter
        with tempfile.NamedTemporaryFile(suffix='.kmz', delete=False) as temp_file:
            temp_file.write(self.file_data)
            temp_file_path = temp_file.name

        try:
            # Use the parent's shared Node.js conversion logic
            return self._convert_via_nodejs(temp_file_path, "KMZ")
        finally:
            # Clean up temporary file
            os.unlink(temp_file_path)
