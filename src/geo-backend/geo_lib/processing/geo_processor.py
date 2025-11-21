"""
Utility functions for geospatial file processing.
This module provides helper functions for processing various geospatial file formats.
Main processing logic has been moved to the processors module.
"""

import re
import xml.etree.ElementTree as ET

import markdownify

from geo_lib.processing.file_types import FileType
from geo_lib.logging.console import get_import_logger

logger = get_import_logger()


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

    # Handle CDATA sections - extract HTML content from CDATA wrapper
    cdata_match = re.search(r'<!\[CDATA\[(.*?)\]\]>', html_content, re.DOTALL)
    if cdata_match:
        html_content = cdata_match.group(1).strip()

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

    # Strip unused style properties that are not part of GeoJSON spec and not used by frontend
    # Keep only: stroke, stroke-width, fill, fill-opacity, marker-color
    unused_style_properties = [
        'stroke-opacity',
        'opacity',
        'weight',
        'dashArray',
        'dash-array',
        'lineCap',
        'line-cap',
        'lineJoin',
        'line-join',
        'color',  # Generic color property - we use specific ones like stroke, fill, marker-color
    ]
    for prop_name in unused_style_properties:
        if prop_name in properties:
            del properties[prop_name]

    # Normalize styles for lines and polygons
    geometry = feature.get('geometry', {})
    geometry_type = geometry.get('type', '').lower() if geometry else ''
    
    # Normalize stroke-width to 2 for lines
    if geometry_type in ['linestring', 'multilinestring']:
        properties['stroke-width'] = 2
    
    # Normalize styles for polygons: stroke-width, fill, and fill-opacity
    if geometry_type in ['polygon', 'multipolygon']:
        properties['stroke-width'] = 2
        
        # Set fill color to match stroke color (or default to #ff0000)
        stroke_color = properties.get('stroke', '#ff0000')
        properties['fill'] = stroke_color
        
        # Set fill-opacity to 10% (0.1)
        properties['fill-opacity'] = 0.1

    return properties


def split_complex_geometries(feature: dict) -> list:
    """
    Split GeometryCollection into separate features.
    
    KML's MultiGeometry converts to GeometryCollection in GeoJSON, so this is the expected
    complex geometry type. MultiPoint and MultiPolygon should not appear and will trigger
    an assertion error if encountered.
    
    Args:
        feature: GeoJSON feature dictionary
        
    Returns:
        List of feature dictionaries (single-item list if not splittable)
        
    Raises:
        AssertionError: If MultiPoint or MultiPolygon geometry types are encountered
    """
    # Handle features with None geometry - skip these as they have no spatial data
    if not feature.get('geometry') or feature['geometry'] is None:
        return []

    geometry_type = feature['geometry']['type']
    
    # Assert that MultiPoint should not appear (KML converts to GeometryCollection)
    if geometry_type == 'MultiPoint':
        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
        error_msg = f"Unexpected MultiPoint geometry in feature '{feature_name}'. KML MultiGeometry should convert to GeometryCollection."
        logger.error(error_msg)
        assert False, error_msg
    
    # Assert that MultiPolygon should not appear (KML converts to GeometryCollection)
    if geometry_type == 'MultiPolygon':
        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
        error_msg = f"Unexpected MultiPolygon geometry in feature '{feature_name}'. KML MultiGeometry should convert to GeometryCollection."
        logger.error(error_msg)
        assert False, error_msg
    
    # Split GeometryCollection into separate features
    if geometry_type == 'GeometryCollection':
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
    
    # For all other geometry types, return as-is
    return [feature]
