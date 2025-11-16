"""
KML file processor for the unified import pipeline.
Handles KML-specific conversion logic.
"""

import json
import os
import re
import subprocess
import tempfile
import time
from typing import Dict, Any

from geo_lib.processing.icon_manager import process_geojson_icons
from geo_lib.processing.logging import DatabaseLogLevel
from .base_processor import BaseProcessor

logger = __import__('logging').getLogger(__name__)


def _remove_namespaces(content: str) -> str:
    """
    Remove namespace declarations and prefixes from KML content.
    The togeojson library doesn't handle namespaced XML well.

    Args:
        content: KML content string

    Returns:
        KML content with namespaces removed
    """
    # Remove namespace declarations
    content = re.sub(r'xmlns:ns\d+="[^"]*"', '', content)
    # Remove namespace prefixes from tags
    content = re.sub(r'ns\d+:', '', content)
    return content


class KMLProcessor(BaseProcessor):
    """
    Processor for KML files.
    Handles KML-specific conversion logic including namespace removal.
    """

    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Convert KML file to GeoJSON using JavaScript togeojson library.
        Also processes remote icons if icon processing is enabled.
        
        Returns:
            GeoJSON data as dictionary
        """
        # Prepare KML content
        content = self._prepare_kml_content()

        # Write content to temporary file to avoid stdin issues
        with tempfile.NamedTemporaryFile(mode='w', suffix='.kml', delete=False, encoding='utf-8') as temp_file:
            temp_file.write(content)
            temp_file_path = temp_file.name

        try:
            geojson_data = self._convert_via_nodejs(temp_file_path, "KML")
            
            # Process icons in GeoJSON
            geojson_data = process_geojson_icons(
                geojson_data,
                file_type='kml',
                file_data=None,
                kml_content=content
            )
            
            return geojson_data
        finally:
            # Clean up temporary file
            os.unlink(temp_file_path)

    def _prepare_kml_content(self) -> str:
        """
        Prepare KML content for conversion by decoding and removing namespaces.
        
        Returns:
            Prepared KML content string
        """
        # Decode content if needed
        if isinstance(self.file_data, str):
            content = self.file_data
        else:
            content = self.file_data.decode('utf-8')

        # Remove namespaces from content to make it compatible with togeojson
        # The togeojson library doesn't handle namespaced XML well
        content = _remove_namespaces(content)
        return content

    def _convert_via_nodejs(self, file_path: str, file_type_name: str) -> Dict[str, Any]:
        """
        Convert file to GeoJSON using JavaScript togeojson library.
        Common conversion logic shared between KML and KMZ processors.
        
        Args:
            file_path: Path to the file to convert
            file_type_name: Name of file type for logging (e.g., "KML", "KMZ")
            
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
