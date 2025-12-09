"""
Geometry validation utilities for GeoJSON.

This module provides validation functions for GeoJSON geometry objects and features,
ensuring they conform to the GeoJSON specification.
"""

from typing import Dict, List, Any, Union


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


def validate_coordinates_values(geometry: Dict[str, Any]) -> None:
    """
    Validate coordinate values in a geometry object.
    Checks for:
    1. None values
    2. Out of bounds (Lat: -90 to 90, Lon: -180 to 180)
    
    Args:
        geometry: GeoJSON geometry object
        
    Raises:
        GeometryValidationError: If coordinates are invalid
    """
    if not isinstance(geometry, dict):
        return

    geom_type = geometry.get('type')

    if geom_type == 'GeometryCollection':
        geometries = geometry.get('geometries', [])
        if not geometries:
            return
        for sub_geom in geometries:
            validate_coordinates_values(sub_geom)
        return

    coordinates = geometry.get('coordinates')
    if not coordinates:
        return

    def check_coord(coord: Union[float, int, None], name: str) -> None:
        if coord is None:
            raise GeometryValidationError(f"Coordinate contains None values")
        if not isinstance(coord, (int, float)):
            raise GeometryValidationError(f"Coordinate values must be numbers, got {type(coord)}")

    def validate_point(point_coords: List[Any]) -> None:
        if not isinstance(point_coords, list) or len(point_coords) < 2:
            raise GeometryValidationError(f"Invalid point coordinates: {point_coords}")

        lon, lat = point_coords[0], point_coords[1]
        check_coord(lon, "Longitude")
        check_coord(lat, "Latitude")

        if not (-180 <= lon <= 180):
            raise GeometryValidationError(f"Longitude {lon} is out of bounds [-180, 180]")
        if not (-90 <= lat <= 90):
            raise GeometryValidationError(f"Latitude {lat} is out of bounds [-90, 90]")

    def traverse_coords(coords: Any, depth: int) -> None:
        if depth == 0:
            validate_point(coords)
        elif isinstance(coords, list):
            for item in coords:
                traverse_coords(item, depth - 1)

    # Determine depth based on geometry type
    if geom_type == 'Point':
        traverse_coords(coordinates, 0)
    elif geom_type in ['LineString', 'MultiPoint']:
        traverse_coords(coordinates, 1)
    elif geom_type in ['Polygon', 'MultiLineString']:
        traverse_coords(coordinates, 2)
    elif geom_type == 'MultiPolygon':
        traverse_coords(coordinates, 3)


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
    validate_coordinates_values(geometry)


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
        validate_coordinates_values(feature_data)
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
