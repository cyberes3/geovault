"""
Helpers for validating and normalizing styling values (colors, icon URLs).

These helpers are used from multiple places:
- Import/bulk styling (`validate_bulk_operations_payload`, `apply_bulk_operations`)
- Feature update APIs

Rules:
- Colors: allow 3-digit or 6-digit hex with leading '#', e.g. '#0f3', '#00ff30'
- Icon URLs: only allow built-in and uploaded icons:
  - 'assets/...' (system icons)
  - '/api/icons/...' (uploaded/user icons)
"""

import re
from typing import Dict, Any

HEX_COLOR_RE = re.compile(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

_DEFAULT_COLOR = '#ff0000'

_LINE_GEOMETRY_TYPES = {'linestring', 'multilinestring'}
_POLYGON_GEOMETRY_TYPES = {'polygon', 'multipolygon'}


def is_valid_hex_color(value: object) -> bool:
    """
    Return True if value is a string hex color in #RGB or #RRGGBB form.
    """
    if not isinstance(value, str):
        return False
    value = value.strip()
    if not value:
        return False
    return bool(HEX_COLOR_RE.match(value))


def normalize_hex_color(value: str) -> str:
    """
    Normalize a hex color (strip whitespace only, preserve case).

    Keeps 3-digit vs 6-digit form as-is; callers can expand if needed.
    Assumes the input has already passed is_valid_hex_color().
    """
    return value.strip()


def is_valid_icon_url(value: object) -> bool:
    """
    Return True if value is an allowed icon URL/path.

    Allowed:
    - 'assets/...'
    - '/api/icons/...'

    Everything else (including external http(s) URLs) is rejected.
    """
    if not isinstance(value, str):
        return False
    value = value.strip()
    if not value:
        return False
    return value.startswith("assets/") or value.startswith("/api/icons/")


def describe_color_format(field_name: str) -> str:
    """
    Helper to produce a consistent error description for color fields.
    """
    return (
        f"bulk_operations.{field_name} must be a valid hex color "
        f"(e.g. #0f3 or #00ff30)"
    )


def describe_icon_format(field_name: str) -> str:
    """
    Helper to produce a consistent error description for icon URL fields.
    """
    return (
        f"bulk_operations.{field_name} must be a built-in or uploaded icon path "
        f"(starting with 'assets/' or '/api/icons/')"
    )


def normalize_feature_colors_and_styles(properties: Dict[str, Any], geometry: Dict[str, Any]) -> Dict[str, Any]:
    """
    Normalize colors and apply style normalization based on geometry type.
    
    This function:
    - Validates colors and sets invalid ones to default red (#FF0000)
    - Preserves original color case (no uppercase conversion)
    - Applies style normalization (stroke-width, fill, fill-opacity) based on geometry type
    
    Args:
        properties: Feature properties dictionary (will be modified in place)
        geometry: Feature geometry dictionary (used to determine geometry type)
        
    Returns:
        Modified properties dictionary with normalized colors and styles
    """

    # Helper to check if geometry contains polygon
    def _has_polygon_geometry(geom: Dict[str, Any]) -> bool:
        geom_type_lower = geom.get('type', '').lower()
        if geom_type_lower in _POLYGON_GEOMETRY_TYPES:
            return True
        if geom_type_lower == 'geometrycollection':
            geometries = geom.get('geometries', [])
            return any(_has_polygon_geometry(g) for g in geometries if isinstance(g, dict))
        return False

    # Validate and normalize colors - set invalid colors to default red
    # Validate marker-color (for points)
    if 'marker-color' in properties:
        color = properties['marker-color']
        if isinstance(color, str) and is_valid_hex_color(color):
            properties['marker-color'] = normalize_hex_color(color)
        else:
            properties['marker-color'] = _DEFAULT_COLOR

    # Validate stroke (for lines and polygons)
    if 'stroke' in properties:
        color = properties['stroke']
        if isinstance(color, str) and is_valid_hex_color(color):
            properties['stroke'] = normalize_hex_color(color)
        else:
            properties['stroke'] = _DEFAULT_COLOR

    # Validate fill (for polygons)
    if 'fill' in properties:
        color = properties['fill']
        if isinstance(color, str) and is_valid_hex_color(color):
            properties['fill'] = normalize_hex_color(color)
        else:
            properties['fill'] = _DEFAULT_COLOR

    # Apply style normalization based on geometry type
    geom_type = geometry.get('type', '').lower()
    has_polygon = _has_polygon_geometry(geometry)

    if geom_type in _LINE_GEOMETRY_TYPES:
        # Lines: normalize stroke-width to 2
        if 'stroke' in properties:
            properties['stroke-width'] = 2
        # Remove fill properties for lines
        if 'fill' in properties:
            del properties['fill']
        if 'fill-opacity' in properties:
            del properties['fill-opacity']

    elif geom_type in _POLYGON_GEOMETRY_TYPES or has_polygon:
        # Polygons: normalize stroke-width, fill, and fill-opacity
        # Also applies to GeometryCollection that contains polygon geometries
        if 'stroke' in properties:
            properties['stroke-width'] = 2
            # Set fill to match stroke color (stroke is already validated above)
            stroke_color = properties.get('stroke', _DEFAULT_COLOR)
            properties['fill'] = stroke_color
            properties['fill-opacity'] = 0.1
        elif 'fill' in properties:
            # If no stroke but has fill, still normalize fill-opacity
            properties['fill-opacity'] = 0.1

    # Points: no style normalization needed (only icon/marker-color)

    return properties
