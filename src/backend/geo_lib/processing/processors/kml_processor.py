"""
KML file processor for the unified import pipeline.
Handles KML-specific conversion logic.
"""

import re
from typing import Dict, Any

from geo_lib.processing.icons.icon_manager import process_geojson_icons
from .base_processor import BaseProcessor
from ...logging.console import get_tagged_logger

_logger = get_tagged_logger('KMLPROCESSOR')


def _remove_namespaces(content: str) -> str:
    """
    Remove problematic namespace prefixes from KML content.
    Historically this was needed because older togeojson versions had trouble
    with *prefixed* namespaces like:
        <ns0:kml xmlns:ns0="http://www.opengis.net/kml/2.2">
    
    However, modern @tmcw/togeojson handles the standard default namespace
    (`xmlns="http://www.opengis.net/kml/2.2"`) correctly, so we avoid stripping
    that (or any generic xmlns declarations) to prevent breaking valid files
    like cdata.kml.
    
    Args:
        content: KML content string
    
    Returns:
        KML content with only prefixed tag names normalized (e.g. <ns0:Placemark> -> <Placemark>).
    """
    # Keep xmlns declarations intact; only strip namespace *prefixes* from tag names.
    # Example: <ns0:Placemark> -> <Placemark>, </ns0:Placemark> -> </Placemark>
    content = re.sub(r'(<\/?)(\w+):', r'\1', content)
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
            file_data=None,
            import_log=self.import_log
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
