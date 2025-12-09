from typing import Any

from geo_lib.validation.coordinate.helpers import CoordinateValidationError, _validate_coordinates_structure, _check_multiple_points_for_swap


def validate_coordinates_for_geometry_type(coordinates: Any, geometry_type: str) -> None:
    """
    Validate coordinates array matches the expected structure for the geometry type.

    Validates:
    1. Coordinates are not None or empty
    2. Structure depth matches geometry type
    3. All coordinates are within bounds (lon: -180 to 180, lat: -90 to 90)
    4. Coordinates are not swapped (lat/lon order)

    Args:
        coordinates: Coordinate array to validate
        geometry_type: Geometry type (Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon)

    Raises:
        CoordinateValidationError: If validation fails with descriptive error message
    """
    # Reject None or empty coordinates
    if coordinates is None:
        raise CoordinateValidationError("Coordinates cannot be null or empty")

    if not isinstance(coordinates, list):
        raise CoordinateValidationError(f"Coordinates must be an array, got {type(coordinates)}")

    geometry_type_lower = geometry_type.lower()

    # Determine expected depth
    depth_map = {
        'point': 0,
        'linestring': 1,
        'polygon': 2,
        'multipoint': 1,
        'multilinestring': 2,
        'multipolygon': 3,
    }

    if geometry_type_lower not in depth_map:
        raise CoordinateValidationError(f"Unsupported geometry type: {geometry_type}")

    expected_depth = depth_map[geometry_type_lower]

    # For Point, empty array is invalid (must have [lon, lat])
    if expected_depth == 0 and len(coordinates) == 0:
        raise CoordinateValidationError("Point coordinates cannot be empty. Must be [longitude, latitude]")

    # For other types, empty arrays are checked in _validate_coordinates_structure
    # but we check here too for early failure
    if expected_depth > 0 and len(coordinates) == 0:
        raise CoordinateValidationError(f"{geometry_type.capitalize()} coordinates cannot be empty")

    # Validate structure and get all points
    points = _validate_coordinates_structure(coordinates, expected_depth, geometry_type.capitalize())

    # Additional swap detection for multi-point geometries
    if len(points) > 1:
        swap_error = _check_multiple_points_for_swap(points)
        if swap_error:
            raise CoordinateValidationError(swap_error)
