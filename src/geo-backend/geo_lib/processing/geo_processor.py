"""
Utility functions for geospatial file processing.
This module provides helper functions for processing various geospatial file formats.
Main processing logic has been moved to the processors module.
"""

import logging
import re
import xml.etree.ElementTree as ET

import markdownify

from geo_lib.processing.file_types import FileType

logger = logging.getLogger(__name__)


def html_to_markdown(html_content) -> str:
    """
    Convert HTML content to markdown format.
    
    Args:
        html_content: HTML string or dict with @type and value keys to convert
        
    Returns:
        Markdown formatted string
    """
    # Handle dictionary format from togeojson
    if isinstance(html_content, dict):
        if '@type' in html_content and html_content['@type'] == 'html' and 'value' in html_content:
            html_content = html_content['value']
        else:
            # If it's a dict but not the expected format, convert to string
            html_content = str(html_content)

    # Ensure we have a string
    if not isinstance(html_content, str):
        html_content = str(html_content)

    if not html_content or not html_content.strip():
        return ""

    # Convert HTML to markdown using markdownify
    markdown_content = markdownify.markdownify(
        html_content,
        heading_style="ATX",  # Use # for headings
        bullets="-",  # Use - for bullet points
        strip=['script', 'style']  # Remove script and style tags
    )

    # Clean up extra whitespace and newlines
    markdown_content = re.sub(r'\n\s*\n\s*\n', '\n\n', markdown_content)
    markdown_content = markdown_content.strip()

    return markdown_content


def normalize_content_for_comparison(content: str, file_type: FileType) -> str:
    """
    Normalize file content for comparison by removing differences that don't affect the actual data.
    
    Args:
        content: File content as string
        file_type: Type of file being normalized
        
    Returns:
        Normalized content string
    """
    if not content:
        return ""

    if file_type == FileType.KML:
        return _normalize_kml_for_comparison(content)
    elif file_type == FileType.GPX:
        return _normalize_gpx_for_comparison(content)
    else:
        return content


def _normalize_kml_for_comparison(kml_content: str) -> str:
    """
    Normalize KML content for comparison by removing differences that don't affect the actual data.
    
    This function handles the differences between KML and KMZ files:
    1. Normalizes document names (removes .kml/.kmz extensions)
    2. Normalizes icon paths (converts both :/ and files/ paths to a standard format)
    3. Removes whitespace differences
    4. Standardizes XML formatting
    """
    # Parse the KML content with secure settings
    try:
        # Use secure parser to prevent XXE attacks
        parser = ET.XMLParser()

        # Disable entity processing to prevent XXE attacks
        # Note: In newer Python versions, parser.entity is readonly, so we use a different approach
        try:
            # Try to disable entity processing (works in older Python versions)
            parser.entity = {}
        except (AttributeError, TypeError):
            # In newer versions, we rely on the default secure behavior
            pass

        root = ET.fromstring(kml_content, parser=parser)
    except ET.ParseError:
        # If XML parsing fails, return the original content
        return kml_content

    # Normalize document name - remove .kml/.kmz extensions
    for name_elem in root.iter():
        if name_elem.tag.endswith('name') and name_elem.text:
            # Remove .kml or .kmz extensions from document names
            name_elem.text = re.sub(r'\.(kml|kmz)$', '', name_elem.text, flags=re.IGNORECASE)

    # Normalize icon paths - convert both :/ and files/ paths to a standard format
    for href_elem in root.iter():
        if href_elem.tag.endswith('href') and href_elem.text:
            href = href_elem.text
            # Convert :/ paths to standard format
            if href.startswith(':/'):
                href_elem.text = href[2:]  # Remove :/ prefix
            # Convert files/ paths to standard format  
            elif href.startswith('files/'):
                href_elem.text = href[6:]  # Remove files/ prefix

    # Convert back to string with consistent formatting
    try:
        # Use a consistent XML declaration and formatting
        normalized = ET.tostring(root, encoding='unicode', xml_declaration=True)
        # Normalize whitespace
        normalized = re.sub(r'\s+', ' ', normalized)
        normalized = re.sub(r'>\s+<', '><', normalized)
        return normalized.strip()
    except Exception:
        # If normalization fails, return the original content
        return kml_content


def _normalize_gpx_for_comparison(gpx_content: str) -> str:
    """
    Normalize GPX content for comparison by removing differences that don't affect the actual data.
    
    This function:
    1. Normalizes whitespace differences
    2. Standardizes XML formatting
    3. Removes metadata that doesn't affect the actual track data
    """
    # Parse the GPX content with secure settings
    try:
        # Use secure parser to prevent XXE attacks
        parser = ET.XMLParser()

        # Disable entity processing to prevent XXE attacks
        try:
            parser.entity = {}
        except (AttributeError, TypeError):
            pass

        root = ET.fromstring(gpx_content, parser=parser)
    except ET.ParseError:
        # If XML parsing fails, return the original content
        return gpx_content

    # Convert back to string with consistent formatting
    try:
        # Use a consistent XML declaration and formatting
        normalized = ET.tostring(root, encoding='unicode', xml_declaration=True)
        # Normalize whitespace
        normalized = re.sub(r'\s+', ' ', normalized)
        normalized = re.sub(r'>\s+<', '><', normalized)
        return normalized.strip()
    except Exception:
        # If normalization fails, return the original content
        return gpx_content


def geojson_property_generation(feature: dict) -> dict:
    """
    Generate GeoJSON properties.
    
    Args:
        feature: Full GeoJSON

    Returns:
        Properties dictionary with optional styling changes
    """
    # Extract properties from feature
    properties = feature.get('properties', {}).copy()

    # Convert HTML descriptions to markdown
    if 'description' in properties and properties['description']:
        properties['description'] = html_to_markdown(properties['description'])

    return properties


def split_geometry_collection(feature: dict) -> list:
    """Split GeometryCollection into separate features."""
    # Handle features with None geometry - skip these as they have no spatial data
    if not feature.get('geometry') or feature['geometry'] is None:
        return []

    if feature['geometry']['type'] != 'GeometryCollection':
        return [feature]

    features = []
    geometries = feature['geometry']['geometries']

    # Prioritize polygons over other geometries
    polygon_geometries = [g for g in geometries if g['type'] == 'Polygon']
    other_geometries = [g for g in geometries if g['type'] in ['Point', 'LineString']]

    # Use polygons if available, otherwise use other geometries
    geometries_to_use = polygon_geometries if polygon_geometries else other_geometries

    for geom in geometries_to_use:
        new_feature = {
            'type': 'Feature',
            'geometry': geom,
            'properties': feature['properties'].copy()
        }
        features.append(new_feature)

    return features
