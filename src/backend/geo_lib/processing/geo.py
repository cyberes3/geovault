"""
Utility functions for geospatial file processing.
This module provides helper functions for processing various geospatial file formats.
Main processing logic has been moved to the processors module.
"""

import logging
import re
from typing import Optional

import markdownify

from geo_lib.logging.console import get_tagged_logger
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError

_logger = get_tagged_logger()


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


def geojson_property_generation(feature: dict) -> dict:
    """
    Generate GeoJSON properties with validation, whitelisting, and style normalization.
    
    This function uses the comprehensive validation function to whitelist keys and normalize styles.
    It also handles HTML to markdown conversion for descriptions.
    
    Args:
        feature: Full GeoJSON Feature

    Returns:
        Properties dictionary with validated, whitelisted keys and normalized styles
    """

    # Extract and preserve system_tags if they exist (they're generated during processing)
    original_system_tags = feature.get('properties', {}).get('system_tags')

    # Convert HTML descriptions to markdown before validation
    properties = feature.get('properties', {}).copy()
    if 'description' in properties and properties['description']:
        properties['description'] = html_to_markdown(properties['description'])
    feature['properties'] = properties

    # Validate, whitelist, and normalize the feature
    normalized_feature = validate_and_normalize_geojson_feature(
        feature,
        preserve_system_tags=original_system_tags
    )
    return normalized_feature.get('properties', {})


def extract_track_created_date(feature: dict) -> Optional[str]:
    """
    Extract the created date from a GPX/KML feature.
    
    For GPX tracks (trk): timestamps are in properties.coordinateProperties.times
    For GPX routes (rte): timestamp is in properties.time
    For KML tracks: timestamps are in properties.coordinateProperties.times
    
    This function extracts the first available timestamp to use as the created date.
    
    Args:
        feature: GeoJSON feature dictionary
        
    Returns:
        ISO timestamp string (e.g., "2001-06-24T15:09:09Z") or None if not found
    """
    geometry = feature.get('geometry', {})
    geometry_type = geometry.get('type', '').lower() if geometry else ''

    # Only process lines (LineString or MultiLineString)
    if geometry_type not in ['linestring', 'multilinestring']:
        return None

    properties = feature.get('properties', {})

    # First, check for GPX route time property (routes have time at the route level)
    if 'time' in properties and properties['time']:
        time_value = properties['time']
        if isinstance(time_value, str):
            return time_value

    # Then check for track timestamps in coordinateProperties.times
    coordinate_properties = properties.get('coordinateProperties', {})

    if not coordinate_properties:
        return None

    times = coordinate_properties.get('times')
    if not times:
        return None

    # Handle MultiLineString: times is an array of arrays
    # Get the first timestamp from the first line
    if geometry_type == 'multilinestring':
        if isinstance(times, list) and len(times) > 0:
            first_line_times = times[0]
            if isinstance(first_line_times, list) and len(first_line_times) > 0:
                first_timestamp = first_line_times[0]
                if isinstance(first_timestamp, str):
                    return first_timestamp
    # Handle LineString: times is a flat array
    elif geometry_type == 'linestring':
        if isinstance(times, list) and len(times) > 0:
            first_timestamp = times[0]
            if isinstance(first_timestamp, str):
                return first_timestamp

    return None


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
        _logger.error(error_msg)
        assert False, error_msg

    # Assert that MultiPolygon should not appear (KML converts to GeometryCollection)
    if geometry_type == 'MultiPolygon':
        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
        error_msg = f"Unexpected MultiPolygon geometry in feature '{feature_name}'. KML MultiGeometry should convert to GeometryCollection."
        _logger.error(error_msg)
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
