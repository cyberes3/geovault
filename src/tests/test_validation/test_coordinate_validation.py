"""
Tests for coordinate validation utilities.
"""
import pytest
from geo_lib.validation.coordinate_validation import (
    validate_coordinates_for_geometry_type,
    CoordinateValidationError,
)


class TestCoordinateValidation:
    """Test coordinate validation functions."""

    def test_valid_point_coordinates(self):
        """Test validation of valid Point coordinates."""
        coordinates = [-104.26, 39.43, 0.0]
        validate_coordinates_for_geometry_type(coordinates, 'Point')

    def test_valid_linestring_coordinates(self):
        """Test validation of valid LineString coordinates."""
        coordinates = [[-104.26, 39.43, 0.0], [-104.25, 39.44, 0.0]]
        validate_coordinates_for_geometry_type(coordinates, 'LineString')

    def test_valid_polygon_coordinates(self):
        """Test validation of valid Polygon coordinates."""
        coordinates = [[[-104.26, 39.43], [-104.25, 39.43], [-104.25, 39.44], [-104.26, 39.44], [-104.26, 39.43]]]
        validate_coordinates_for_geometry_type(coordinates, 'Polygon')

    def test_empty_coordinates_rejected(self):
        """Test that empty coordinates are rejected."""
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([], 'Point')
        assert 'empty' in str(exc_info.value).lower()

        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([], 'LineString')
        assert 'empty' in str(exc_info.value).lower()

    def test_null_coordinates_rejected(self):
        """Test that null coordinates are rejected."""
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(None, 'Point')
        assert 'null' in str(exc_info.value).lower() or 'empty' in str(exc_info.value).lower()

    def test_wrong_structure_for_point(self):
        """Test that wrong structure for Point is rejected."""
        # Point expects [lon, lat], not [[lon, lat]]
        with pytest.raises(CoordinateValidationError):
            validate_coordinates_for_geometry_type([[-104.26, 39.43]], 'Point')

    def test_wrong_structure_for_linestring(self):
        """Test that wrong structure for LineString is rejected."""
        # LineString expects [[lon, lat], ...], not [lon, lat]
        with pytest.raises(CoordinateValidationError):
            validate_coordinates_for_geometry_type([-104.26, 39.43], 'LineString')

    def test_coordinates_out_of_bounds_longitude(self):
        """Test that coordinates with longitude out of bounds are rejected."""
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([181.0, 39.43], 'Point')
        assert 'bounds' in str(exc_info.value).lower() or 'invalid' in str(exc_info.value).lower()

        with pytest.raises(CoordinateValidationError):
            validate_coordinates_for_geometry_type([-181.0, 39.43], 'Point')

    def test_coordinates_out_of_bounds_latitude(self):
        """Test that coordinates with latitude out of bounds are rejected."""
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([-104.26, 91.0], 'Point')
        assert 'bounds' in str(exc_info.value).lower() or 'swapped' in str(exc_info.value).lower()

        with pytest.raises(CoordinateValidationError):
            validate_coordinates_for_geometry_type([-104.26, -91.0], 'Point')

    def test_coordinates_at_valid_limits(self):
        """Test that coordinates at valid limits pass."""
        validate_coordinates_for_geometry_type([180.0, 90.0], 'Point')
        validate_coordinates_for_geometry_type([-180.0, -90.0], 'Point')

    def test_coordinates_with_nan_rejected(self):
        """Test that coordinates with NaN are rejected."""
        import math
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([math.nan, 39.43], 'Point')
        assert 'nan' in str(exc_info.value).lower()

    def test_coordinates_with_infinity_rejected(self):
        """Test that coordinates with Infinity are rejected."""
        import math
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([math.inf, 39.43], 'Point')
        assert 'infinity' in str(exc_info.value).lower()

    def test_coordinates_with_none_values_rejected(self):
        """Test that coordinates with None values are rejected."""
        with pytest.raises(CoordinateValidationError):
            validate_coordinates_for_geometry_type([None, 39.43], 'Point')

    def test_coordinates_with_invalid_types_rejected(self):
        """Test that coordinates with invalid types are rejected."""
        with pytest.raises(CoordinateValidationError):
            validate_coordinates_for_geometry_type(['invalid', 39.43], 'Point')

    def test_unsupported_geometry_type(self):
        """Test that unsupported geometry types are rejected."""
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([-104.26, 39.43], 'InvalidType')
        assert 'unsupported' in str(exc_info.value).lower()

    def test_multipoint_coordinates_valid(self):
        """Test validation of valid MultiPoint coordinates."""
        coordinates = [[-104.26, 39.43], [-104.25, 39.44]]
        validate_coordinates_for_geometry_type(coordinates, 'MultiPoint')

    def test_multilinestring_coordinates_valid(self):
        """Test validation of valid MultiLineString coordinates."""
        coordinates = [[[-104.26, 39.43], [-104.25, 39.44]], [[-104.24, 39.45], [-104.23, 39.46]]]
        validate_coordinates_for_geometry_type(coordinates, 'MultiLineString')

    def test_multipolygon_coordinates_valid(self):
        """Test validation of valid MultiPolygon coordinates."""
        coordinates = [[[[-104.26, 39.43], [-104.25, 39.43], [-104.25, 39.44], [-104.26, 39.44], [-104.26, 39.43]]]]
        validate_coordinates_for_geometry_type(coordinates, 'MultiPolygon')

    def test_linestring_empty_array_rejected(self):
        """Test that LineString with empty array is rejected."""
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([], 'LineString')
        assert 'empty' in str(exc_info.value).lower()

    def test_polygon_empty_array_rejected(self):
        """Test that Polygon with empty array is rejected."""
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([], 'Polygon')
        assert 'empty' in str(exc_info.value).lower()

    def test_linestring_empty_ring_rejected(self):
        """Test that LineString with empty ring is rejected."""
        with pytest.raises(CoordinateValidationError):
            validate_coordinates_for_geometry_type([[]], 'LineString')

    def test_polygon_empty_ring_rejected(self):
        """Test that Polygon with empty ring is rejected."""
        with pytest.raises(CoordinateValidationError):
            validate_coordinates_for_geometry_type([[]], 'Polygon')

    def test_valid_longitude_greater_than_90(self):
        """Test that valid longitudes > 90 are accepted (not flagged as swapped)."""
        # This is a valid longitude (e.g., Colorado, USA)
        coordinates = [-104.26, 39.43]
        validate_coordinates_for_geometry_type(coordinates, 'Point')

    def test_lat_lon_swap_detection_lat_out_of_bounds(self):
        """Test that lat/lon swap is detected when lat is out of bounds."""
        # If lat > 90, it's clearly wrong
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type([-104.26, 120.0], 'Point')
        assert 'swapped' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()


