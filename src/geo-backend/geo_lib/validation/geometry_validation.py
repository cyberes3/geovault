"""
Geometry validation utilities for GeoJSON.

This module provides validation functions for GeoJSON geometry objects and features,
ensuring they conform to the GeoJSON specification.
"""

from typing import Dict, List, Any


class GeometryValidationError(Exception):
    """Exception raised when geometry validation fails."""
    pass


# Valid GeoJSON geometry types
VALID_GEOMETRY_TYPES = [
    'Point',
    'LineString',
    'Polygon',
    'MultiPoint',
    'MultiLineString',
    'MultiPolygon',
    'GeometryCollection'
]


def get_valid_geometry_types() -> List[str]:
    """
    Get list of valid GeoJSON geometry types.
    
    Returns:
        List of valid geometry type strings
    """
    return VALID_GEOMETRY_TYPES.copy()


def is_valid_geometry_type(geom_type: str) -> bool:
    """
    Check if a geometry type is valid.
    
    Args:
        geom_type: Geometry type string to validate
        
    Returns:
        True if valid, False otherwise
    """
    return geom_type in VALID_GEOMETRY_TYPES


def validate_geometry(geometry: Dict[str, Any]) -> None:
    """
    Validate a GeoJSON geometry object.
    
    Args:
        geometry: Dictionary representing a GeoJSON geometry object
        
    Raises:
        GeometryValidationError: If geometry is invalid
    """
    if not isinstance(geometry, dict):
        raise GeometryValidationError('Geometry must be a dictionary object')
    
    geom_type = geometry.get('type')
    if not geom_type or not isinstance(geom_type, str):
        raise GeometryValidationError('Geometry must have a type string')
    
    if not is_valid_geometry_type(geom_type):
        raise GeometryValidationError(
            f'Invalid geometry type: {geom_type}. Must be one of: {", ".join(VALID_GEOMETRY_TYPES)}'
        )
    
    # Validate required fields based on geometry type
    if geom_type == 'GeometryCollection':
        if 'geometries' not in geometry or not isinstance(geometry.get('geometries'), list):
            raise GeometryValidationError('GeometryCollection must have a geometries array')
    elif 'coordinates' not in geometry:
        raise GeometryValidationError(f'{geom_type} geometry must have coordinates')


def validate_feature_geometry(feature_data: Dict[str, Any]) -> None:
    """
    Validate the geometry within a GeoJSON Feature object.
    
    Args:
        feature_data: Dictionary representing a GeoJSON Feature object
        
    Raises:
        GeometryValidationError: If feature geometry is invalid
    """
    if not isinstance(feature_data, dict):
        raise GeometryValidationError('Feature must be a dictionary object')
    
    if feature_data.get('type') != 'Feature':
        raise GeometryValidationError('Feature must have type "Feature"')
    
    geometry = feature_data.get('geometry')
    if not geometry or not isinstance(geometry, dict):
        raise GeometryValidationError('Feature must have a valid geometry object')
    
    # Use the geometry validation function
    validate_geometry(geometry)


def normalize_and_validate_feature_update(
    feature_data: Dict[str, Any],
    original_properties: Dict[str, Any]
) -> Dict[str, Any]:
    """
    Normalize and validate a Feature or geometry object for updates.
    If a geometry object is provided, it's wrapped in a Feature with original properties.
    
    Args:
        feature_data: GeoJSON Feature object or geometry object
        original_properties: Original feature properties to preserve
        
    Returns:
        Validated and normalized GeoJSON Feature object
        
    Raises:
        GeometryValidationError: If validation fails
    """
    if not isinstance(feature_data, dict):
        raise GeometryValidationError('Request body must be a valid GeoJSON object')
    
    geom_type = feature_data.get('type')
    
    if geom_type == 'Feature':
        # Validate Feature object
        validate_feature_geometry(feature_data)
        return feature_data
    elif geom_type in VALID_GEOMETRY_TYPES:
        # Validate geometry object and normalize to Feature
        validate_geometry(feature_data)
        # Extract only geometry fields (ignore any properties)
        allowed_fields = {'type', 'coordinates', 'geometries'}
        geometry = {k: v for k, v in feature_data.items() if k in allowed_fields}
        # Wrap in Feature object with original properties
        return {
            'type': 'Feature',
            'geometry': geometry,
            'properties': original_properties.copy()
        }
    else:
        raise GeometryValidationError('Request body must be a valid GeoJSON Feature or geometry object')

