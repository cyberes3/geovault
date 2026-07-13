import math
from typing import List, Tuple

from geo_lib.reverse_geocoding.constants import COORDINATE_PRECISION
from geo_lib.processing.duplicate_detection.constants import COORDINATE_TOLERANCE


def _decimal_places_for_tolerance(tolerance: float) -> int:
    """Convert a coordinate tolerance (e.g. 5e-6 degrees) to a rounding precision in decimal places."""
    if tolerance <= 0:
        return 6
    return max(0, -math.floor(math.log10(tolerance)))


def normalize_coordinates(coords: List, tolerance: float = COORDINATE_TOLERANCE) -> List:
    """Normalize coordinates by rounding to a precision derived from `tolerance`."""
    if not coords:
        return []

    if isinstance(coords[0], (int, float)):
        # Single coordinate pair
        decimal_places = _decimal_places_for_tolerance(tolerance)
        return [round(coord, decimal_places) for coord in coords]
    else:
        # Nested coordinates (LineString or Polygon)
        return [normalize_coordinates(coord, tolerance) for coord in coords]


def _horizontal_components_match(
        c1: List,
        c2: List,
        tolerance: float = COORDINATE_TOLERANCE,
) -> bool:
    """Match longitude and latitude within tolerance; ignore elevation/extra dimensions."""
    if len(c1) < 2 or len(c2) < 2:
        return False
    return (
        abs(c1[0] - c2[0]) <= tolerance
        and abs(c1[1] - c2[1]) <= tolerance
    )


def geometries_match(
        coords1: List,
        coords2: List,
        tolerance: float = COORDINATE_TOLERANCE,
) -> bool:
    """
    Check if two GeoJSON coordinate arrays represent the same geometry within tolerance.

    Requires the same nesting structure and vertex/ring counts at every level. At each
    position, longitude and latitude must match within tolerance (elevation is ignored).
    Used after spatial-index prefilters so overlapping paths (e.g. repeat hikes on the
    same trail) are not treated as duplicates.
    """
    if not coords1 and not coords2:
        return True
    if not coords1 or not coords2:
        return False

    first1 = coords1[0]
    first2 = coords2[0]

    if isinstance(first1, (int, float)):
        if not isinstance(first2, (int, float)):
            return False
        return _horizontal_components_match(coords1, coords2, tolerance)

    if isinstance(first2, (int, float)):
        return False
    if len(coords1) != len(coords2):
        return False
    return all(
        geometries_match(c1, c2, tolerance)
        for c1, c2 in zip(coords1, coords2)
    )


def round_coordinate(latitude: float, longitude: float) -> Tuple[float, float]:
    """
    Round coordinates to cache precision (~111m).

    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate

    Returns:
        Tuple of (rounded_lat, rounded_lon)
    """
    return round(latitude, COORDINATE_PRECISION), round(longitude, COORDINATE_PRECISION)
