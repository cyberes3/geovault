"""
GeoJSON validation and normalization with key whitelisting.

This module provides a comprehensive validation function that:
- Uses Pydantic models to whitelist only explicitly allowed keys at all levels
- Automatically removes all non-whitelisted keys via Pydantic validation
- Performs style normalization
- Validates structure using Pydantic
"""

from typing import Dict, Any


def _has_polygon_geometry(geometry: Dict[str, Any]) -> bool:
    """
    Check if a geometry contains any polygon geometries.
    
    For GeometryCollection, recursively checks all geometries.
    For Polygon/MultiPolygon, returns True.
    Otherwise, returns False.
    
    Args:
        geometry: Geometry dictionary
        
    Returns:
        True if geometry contains any polygon geometries, False otherwise
    """
    geom_type_lower = geometry.get('type', '').lower()

    # Check if this is a polygon type (case-insensitive)
    if geom_type_lower in {'polygon', 'multipolygon'}:
        return True

    # For GeometryCollection, recursively check all geometries
    if geom_type_lower == 'geometrycollection':
        geometries = geometry.get('geometries', [])
        return any(_has_polygon_geometry(g) for g in geometries if isinstance(g, dict))

    return False
