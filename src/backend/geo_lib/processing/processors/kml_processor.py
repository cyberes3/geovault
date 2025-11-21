"""
KML file processor for the unified import pipeline.
Handles KML-specific conversion logic.
"""

import re
from typing import Dict, Any

from geo_lib.processing.icon_manager import process_geojson_icons
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

        # Convert using shared temp file helper
        geojson_data = self._convert_to_geojson(content, '.kml', 'KML', is_text=True)

        # Process icons in GeoJSON
        geojson_data = process_geojson_icons(
            geojson_data,
            file_type='kml',
            file_data=None
        )

        return geojson_data

    def _prepare_kml_content(self) -> str:
        """
        Prepare KML content for conversion by decoding and removing namespaces.
        
        Returns:
            Prepared KML content string
        """
        # Decode content using shared helper
        content = self._decode_content()

        # Remove namespaces from content to make it compatible with togeojson
        # The togeojson library doesn't handle namespaced XML well
        content = _remove_namespaces(content)
        return content
