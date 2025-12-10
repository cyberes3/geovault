"""
Styling utilities for import operations.
Handles icon stripping and bulk styling operations for features.
"""

import copy
from typing import Dict, Any, List

from geo_lib.validation.styling_validation import (
    is_valid_hex_color,
    is_valid_icon_url,
    normalize_hex_color,
)


def strip_icon_properties(feature: dict) -> dict:
    """
    Remove icon-related properties from a feature.
    
    Args:
        feature: Feature dictionary with properties
        
    Returns:
        Feature dictionary with icon properties removed
    """
    if not isinstance(feature, dict) or 'properties' not in feature:
        return feature

    # Common property names that might contain icon hrefs
    icon_property_names = [
        'marker-symbol',
        'icon',
        'icon-href',
        'iconUrl',
        'icon_url',
        'marker-icon',
        'symbol',
        'styleUrl',  # KML style URLs might reference icons
    ]

    # Remove icon properties
    for prop_name in icon_property_names:
        if prop_name in feature['properties']:
            del feature['properties'][prop_name]

    # Also check nested structures (e.g., style objects)
    def remove_icons_from_dict(d):
        if not isinstance(d, dict):
            return
        for key, value in list(d.items()):
            if key in icon_property_names:
                del d[key]
            elif isinstance(value, dict):
                remove_icons_from_dict(value)
            elif isinstance(value, list):
                for item in value:
                    if isinstance(item, dict):
                        remove_icons_from_dict(item)

    remove_icons_from_dict(feature['properties'])

    return feature


def apply_bulk_operations(features: List[Dict[str, Any]], bulk_ops: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    Apply bulk operations (tags, styling) to a list of features.
    Shared utility function used by both single and bulk import jobs.
    
    Args:
        features: List of GeoJSON features
        bulk_ops: Dictionary containing bulk operations (tags, pointColor, pointIcon, lineColor, polyColor)
        
    Returns:
        List of features with bulk operations applied
    """
    if not bulk_ops:
        return features

    result = []
    for feature in features:
        # Skip duplicates
        if feature.get('properties', {}).get('isDuplicate', False):
            result.append(feature)
            continue

        # Create a copy to avoid mutating the original
        modified_feature = copy.deepcopy(feature)

        # Initialize properties if not present
        if 'properties' not in modified_feature:
            modified_feature['properties'] = {}

        # Apply tags (merge with existing tags, avoiding duplicates)
        if bulk_ops.get('tags') and len(bulk_ops['tags']) > 0:
            if 'tags' not in modified_feature['properties']:
                modified_feature['properties']['tags'] = []

            # Merge tags, avoiding duplicates
            existing_tags = set(tag.lower() for tag in modified_feature['properties']['tags'])
            for tag in bulk_ops['tags']:
                lower_tag = tag.lower()
                if lower_tag not in existing_tags:
                    modified_feature['properties']['tags'].append(lower_tag)
                    existing_tags.add(lower_tag)

        geometry_type = modified_feature.get('geometry', {}).get('type')

        # Apply point styling (only if value is not None)
        # Applies to both Point and MultiPoint
        DEFAULT_COLOR = '#ff0000'

        if geometry_type in ('Point', 'MultiPoint'):
            if bulk_ops.get('pointColor') is not None:
                color = bulk_ops['pointColor']
                if is_valid_hex_color(color):
                    modified_feature['properties']['marker-color'] = normalize_hex_color(
                        color
                    )
                else:
                    # Invalid color - set to default red
                    modified_feature['properties']['marker-color'] = DEFAULT_COLOR

            if bulk_ops.get('pointIcon') is not None:
                icon_value = bulk_ops['pointIcon']
                if is_valid_icon_url(icon_value):
                    # Keep a single canonical property plus common aliases for compatibility
                    modified_feature['properties']['icon'] = icon_value
                    modified_feature['properties']['icon_url'] = icon_value
                    modified_feature['properties']['iconUrl'] = icon_value
                    modified_feature['properties']['icon-href'] = icon_value

        # Apply line styling (only if value is not None)
        # Applies to both LineString and MultiLineString
        if geometry_type in ('LineString', 'MultiLineString'):
            if bulk_ops.get('lineColor') is not None:
                color = bulk_ops['lineColor']
                if is_valid_hex_color(color):
                    modified_feature['properties']['stroke'] = normalize_hex_color(
                        color
                    )
                else:
                    # Invalid color - set to default red
                    modified_feature['properties']['stroke'] = DEFAULT_COLOR

        # Apply polygon styling (only if value is not None)
        if geometry_type in ('Polygon', 'MultiPolygon'):
            if bulk_ops.get('polyColor') is not None:
                color = bulk_ops['polyColor']
                if is_valid_hex_color(color):
                    norm_color = normalize_hex_color(color)
                    # Set both stroke (border) and fill to the same color
                    # Fill should have 10% opacity
                    modified_feature['properties']['stroke'] = norm_color
                    modified_feature['properties']['fill'] = norm_color
                    modified_feature['properties']['fill-opacity'] = 0.1
                else:
                    # Invalid color - set to default red
                    modified_feature['properties']['stroke'] = DEFAULT_COLOR
                    modified_feature['properties']['fill'] = DEFAULT_COLOR
                    modified_feature['properties']['fill-opacity'] = 0.1

        result.append(modified_feature)

    return result
