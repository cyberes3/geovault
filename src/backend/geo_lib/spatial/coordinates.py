from typing import List, Tuple

from geo_lib.processing.duplicate_detection.constants import COORDINATE_TOLERANCE


def normalize_coordinates(coords: List, tolerance: float = COORDINATE_TOLERANCE) -> List:
    """Normalize coordinates by rounding to specified tolerance."""
    if not coords:
        return []

    if isinstance(coords[0], (int, float)):
        # Single coordinate pair
        return [round(coord, 6) for coord in coords]
    else:
        # Nested coordinates (LineString or Polygon)
        return [normalize_coordinates(coord, tolerance) for coord in coords]


def coordinates_match(coord1: List, coord2: List, tolerance: float = COORDINATE_TOLERANCE) -> bool:
    """Check if two coordinate sets match within tolerance."""
    norm1 = normalize_coordinates(coord1, tolerance)
    norm2 = normalize_coordinates(coord2, tolerance)
    return norm1 == norm2


def round_coordinate(latitude: float, longitude: float) -> Tuple[float, float]:
    """
    Round coordinates to cache precision (~111m).

    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate

    Returns:
        Tuple of (rounded_lat, rounded_lon)
    """
    return round(latitude, 3), round(longitude, 3)
