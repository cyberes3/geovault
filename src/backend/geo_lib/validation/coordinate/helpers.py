"""
Coordinate validation utilities for GeoJSON coordinates.

This module provides validation functions for coordinate arrays, ensuring they:
1. Match the expected structure for the geometry type
2. Are within valid bounds (lon: -180 to 180, lat: -90 to 90)
3. Are not swapped (lat/lon order)
4. Use coordinate-parser library for robust coordinate validation
"""

from typing import List, Any, Tuple, Optional

from geo_lib.coordinate_parser import parse_coordinate


class CoordinateValidationError(Exception):
    """Exception raised when coordinate validation fails."""
    pass


def _validate_point_coordinate(point: List[Any]) -> Tuple[float, float]:
    """
    Validate a single point coordinate [lon, lat] or [lon, lat, elevation].
    
    Args:
        point: Coordinate array [lon, lat] or [lon, lat, elevation]
        
    Returns:
        Tuple of (lon, lat)
        
    Raises:
        CoordinateValidationError: If coordinate is invalid
    """
    if not isinstance(point, list):
        raise CoordinateValidationError(f"Point coordinate must be an array, got {type(point)}")

    if len(point) < 2:
        raise CoordinateValidationError(f"Point coordinate must have at least 2 elements [lon, lat], got {len(point)}")

    lon = point[0]
    lat = point[1]

    # Check types
    if not isinstance(lon, (int, float)):
        raise CoordinateValidationError(f"Longitude must be a number, got {type(lon)}")
    if not isinstance(lat, (int, float)):
        raise CoordinateValidationError(f"Latitude must be a number, got {type(lat)}")

    # Check for None values
    if lon is None or lat is None:
        raise CoordinateValidationError("Coordinate contains None values")

    # Convert to float for bounds checking
    try:
        lon = float(lon)
        lat = float(lat)
    except (ValueError, TypeError, OverflowError):
        raise CoordinateValidationError("Coordinate values must be valid numbers")

    # Check for NaN or Infinity
    import math
    if math.isnan(lon) or math.isnan(lat):
        raise CoordinateValidationError("Coordinate values cannot be NaN")
    if math.isinf(lon) or math.isinf(lat):
        raise CoordinateValidationError("Coordinate values cannot be Infinity")

    # Validate using coordinate-parser library for robust validation
    try:
        # Validate longitude using coordinate-parser
        parsed_lon = parse_coordinate(lon, coord_type="longitude", validate=True)
        # Validate latitude using coordinate-parser
        parsed_lat = parse_coordinate(lat, coord_type="latitude", validate=True)
        # Use parsed values (ensures consistency with coordinate-parser's output)
        lon = float(parsed_lon)
        lat = float(parsed_lat)
    except ValueError as e:
        # coordinate-parser validation failed
        raise CoordinateValidationError(f"Invalid coordinate: {str(e)}")
    except Exception as e:
        # Other errors from coordinate-parser
        raise CoordinateValidationError(f"Coordinate validation failed: {str(e)}")

    # Additional bounds check (coordinate-parser should have already validated, but double-check)
    if not (-180 <= lon <= 180):
        raise CoordinateValidationError(f"Longitude {lon} is out of bounds [-180, 180]")
    if not (-90 <= lat <= 90):
        raise CoordinateValidationError(f"Latitude {lat} is out of bounds [-90, 90]")

    # Detect lat/lon swapping
    # Only flag as swapped if coordinates are clearly wrong
    # Don't flag valid longitudes just because abs(lon) > 90 (many valid longitudes are > 90)

    # Case 1: Latitude is clearly out of bounds (abs(lat) > 90)
    # This means the value in the lat position is actually a longitude
    if abs(lat) > 90:
        raise CoordinateValidationError(
            f"Coordinates appear to be swapped. Latitude {lat} is outside valid range [-90, 90]."
        )

    # Note: We don't check for lon > 180 here because that's already caught by bounds check above
    # and would fail before we get to swap detection

    # Case 2: Both values are in valid ranges, but pattern suggests a swap
    # Only flag if lon is in lat range (-90 to 90) AND lat is clearly a longitude value
    # This catches cases like [40, -120] where 40 could be lat but -120 is clearly a lon
    # But we've already validated that abs(lat) <= 90, so if we get here, both are valid
    # We can't reliably detect swaps when both values are in valid ranges
    # So we only flag obvious cases that passed bounds but are clearly wrong
    # (This case is now empty since bounds check handles everything)

    return lon, lat


def _check_multiple_points_for_swap(points: List[Tuple[float, float]]) -> Optional[str]:
    """
    Check multiple points to detect consistent lat/lon swapping pattern.
    
    Args:
        points: List of (lon, lat) tuples
        
    Returns:
        Error message if swap detected, None otherwise
    """
    if len(points) < 2:
        return None

    # Count how many points look swapped
    swap_count = 0
    total_checked = 0

    for lon, lat in points:
        # Skip if either is clearly out of bounds (already caught by _validate_point_coordinate)
        if abs(lon) > 180 or abs(lat) > 90:
            continue

        total_checked += 1

        # If both are in valid ranges but lon is in lat range and lat is in lon range
        if (-90 <= lon <= 90) and (-180 <= lat <= 180):
            # Check if this looks like a swap
            if abs(lon) > abs(lat):
                swap_count += 1

    # If majority of points look swapped, report it
    if total_checked >= 2 and swap_count > total_checked * 0.5:
        return (
            f"Multiple coordinates appear to be swapped. "
            f"Expected [longitude, latitude] format. "
            f"Longitude should be first (range -180 to 180), latitude second (range -90 to 90)."
        )

    return None


def _validate_coordinates_structure(coordinates: Any, expected_depth: int, geometry_type: str) -> List[Tuple[float, float]]:
    """
    Validate coordinate structure and depth, returning all points for swap detection.
    
    Args:
        coordinates: Coordinate array
        expected_depth: Expected nesting depth (0 for Point, 1 for LineString/MultiPoint, etc.)
        geometry_type: Geometry type name for error messages
        
    Returns:
        List of (lon, lat) tuples from all points
        
    Raises:
        CoordinateValidationError: If structure is invalid
    """
    if not isinstance(coordinates, list):
        raise CoordinateValidationError(f"{geometry_type} coordinates must be an array")

    if expected_depth == 0:
        # Point: [lon, lat] or [lon, lat, elevation]
        lon, lat = _validate_point_coordinate(coordinates)
        return [(lon, lat)]

    elif expected_depth == 1:
        # LineString or MultiPoint: [[lon, lat], ...]
        if len(coordinates) == 0:
            raise CoordinateValidationError(f"{geometry_type} must have at least one coordinate")

        points = []
        for i, point in enumerate(coordinates):
            try:
                lon, lat = _validate_point_coordinate(point)
                points.append((lon, lat))
            except CoordinateValidationError as e:
                raise CoordinateValidationError(f"Invalid coordinate at index {i}: {str(e)}")

        return points

    elif expected_depth == 2:
        # Polygon or MultiLineString: [[[lon, lat], ...], ...]
        if len(coordinates) == 0:
            raise CoordinateValidationError(f"{geometry_type} must have at least one ring/line")

        all_points = []
        for ring_idx, ring in enumerate(coordinates):
            if not isinstance(ring, list):
                raise CoordinateValidationError(f"{geometry_type} ring/line at index {ring_idx} must be an array")
            if len(ring) == 0:
                raise CoordinateValidationError(f"{geometry_type} ring/line at index {ring_idx} must have at least one coordinate")

            for point_idx, point in enumerate(ring):
                try:
                    lon, lat = _validate_point_coordinate(point)
                    all_points.append((lon, lat))
                except CoordinateValidationError as e:
                    raise CoordinateValidationError(
                        f"Invalid coordinate at ring/line {ring_idx}, point {point_idx}: {str(e)}"
                    )

        return all_points

    elif expected_depth == 3:
        # MultiPolygon: [[[[lon, lat], ...], ...], ...]
        if len(coordinates) == 0:
            raise CoordinateValidationError(f"{geometry_type} must have at least one polygon")

        all_points = []
        for poly_idx, polygon in enumerate(coordinates):
            if not isinstance(polygon, list):
                raise CoordinateValidationError(f"{geometry_type} polygon at index {poly_idx} must be an array")
            if len(polygon) == 0:
                raise CoordinateValidationError(f"{geometry_type} polygon at index {poly_idx} must have at least one ring")

            for ring_idx, ring in enumerate(polygon):
                if not isinstance(ring, list):
                    raise CoordinateValidationError(
                        f"{geometry_type} polygon {poly_idx}, ring {ring_idx} must be an array"
                    )
                if len(ring) == 0:
                    raise CoordinateValidationError(
                        f"{geometry_type} polygon {poly_idx}, ring {ring_idx} must have at least one coordinate"
                    )

                for point_idx, point in enumerate(ring):
                    try:
                        lon, lat = _validate_point_coordinate(point)
                        all_points.append((lon, lat))
                    except CoordinateValidationError as e:
                        raise CoordinateValidationError(
                            f"Invalid coordinate at polygon {poly_idx}, ring {ring_idx}, point {point_idx}: {str(e)}"
                        )

        return all_points

    else:
        raise CoordinateValidationError(f"Unsupported coordinate depth: {expected_depth}")
