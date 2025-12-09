"""
Tests for coordinate validation utilities.
"""
import pytest
from geo_lib.validation.coordinate.helpers import (
    CoordinateValidationError,
)
from geo_lib.validation.coordinate.coordinate_validation import validate_coordinates_for_geometry_type


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
        assert 'latitude' in str(exc_info.value).lower() and ('outside' in str(exc_info.value).lower() or 'range' in str(exc_info.value).lower())

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
        # Error should mention latitude issue
        assert 'latitude' in str(exc_info.value).lower()


class TestCoordinateValidationInComplexGeometries:
    """Test that ALL coordinates are validated in complex geometries, not just first/last."""

    def test_linestring_invalid_coordinate_at_start(self):
        """Test that invalid coordinate at the START of a LineString is caught."""
        coordinates = [
            [181.0, 39.43],    # INVALID - longitude out of bounds
            [-104.25, 39.44],  # Valid
            [-104.24, 39.45],  # Valid
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'LineString')
        assert 'index 0' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_linestring_invalid_coordinate_in_middle(self):
        """Test that invalid coordinate in the MIDDLE of a LineString is caught."""
        coordinates = [
            [-104.26, 39.43],  # Valid
            [-104.25, 39.44],  # Valid
            [181.0, 39.45],    # INVALID - longitude out of bounds
            [-104.23, 39.46],  # Valid
            [-104.22, 39.47],  # Valid
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'LineString')
        assert 'index 2' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_linestring_invalid_coordinate_at_end(self):
        """Test that invalid coordinate at the END of a LineString is caught."""
        coordinates = [
            [-104.26, 39.43],  # Valid
            [-104.25, 39.44],  # Valid
            [-104.24, 39.45],  # Valid
            [-104.23, 91.0],   # INVALID - latitude out of bounds
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'LineString')
        assert 'index 3' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_polygon_invalid_coordinate_in_middle_of_ring(self):
        """Test that invalid coordinate in middle of polygon ring is caught."""
        coordinates = [[
            [-104.26, 39.43],  # Valid
            [-104.25, 39.43],  # Valid
            [-104.25, 91.0],   # INVALID - latitude out of bounds
            [-104.26, 39.44],  # Valid
            [-104.26, 39.43],  # Valid (closing)
        ]]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'Polygon')
        assert 'point 2' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_polygon_invalid_coordinate_with_nan_in_middle(self):
        """Test that NaN coordinate in middle of polygon ring is caught."""
        import math
        coordinates = [[
            [-104.26, 39.43],    # Valid
            [-104.25, 39.43],    # Valid
            [math.nan, 39.44],   # INVALID - NaN
            [-104.26, 39.44],    # Valid
            [-104.26, 39.43],    # Valid (closing)
        ]]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'Polygon')
        assert 'nan' in str(exc_info.value).lower()

    def test_polygon_with_hole_invalid_coordinate_in_hole(self):
        """Test that invalid coordinate in polygon hole (inner ring) is caught."""
        coordinates = [
            # Outer ring - all valid
            [[-104.26, 39.43], [-104.20, 39.43], [-104.20, 39.48], [-104.26, 39.48], [-104.26, 39.43]],
            # Inner ring (hole) - has invalid coordinate
            [[-104.25, 39.44], [181.0, 39.45], [-104.23, 39.46], [-104.25, 39.46], [-104.25, 39.44]]
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'Polygon')
        assert 'ring' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_multipoint_invalid_coordinate_in_middle(self):
        """Test that invalid coordinate in middle of MultiPoint is caught."""
        coordinates = [
            [-104.26, 39.43],  # Valid
            [-104.25, 39.44],  # Valid
            [-104.24, 91.0],   # INVALID - latitude out of bounds
            [-104.23, 39.46],  # Valid
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiPoint')
        assert 'index 2' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_multilinestring_invalid_in_first_line(self):
        """Test that invalid coordinate in first line of MultiLineString is caught."""
        coordinates = [
            # First line - has invalid coordinate
            [[-104.26, 39.43], [181.0, 39.44]],
            # Second line - all valid
            [[-104.24, 39.45], [-104.23, 39.46]],
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiLineString')
        # Should mention invalid coordinate and which line/point
        assert 'invalid' in str(exc_info.value).lower() and 'longitude' in str(exc_info.value).lower()

    def test_multilinestring_invalid_in_second_line(self):
        """Test that invalid coordinate in second line of MultiLineString is caught."""
        coordinates = [
            # First line - all valid
            [[-104.26, 39.43], [-104.25, 39.44]],
            # Second line - has invalid coordinate
            [[-104.24, 39.45], [-104.23, 91.0]],
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiLineString')
        assert 'line 1' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_multilinestring_invalid_in_middle_of_second_line(self):
        """Test that invalid coordinate in middle of second line is caught."""
        coordinates = [
            # First line - all valid
            [[-104.26, 39.43], [-104.25, 39.44], [-104.24, 39.45]],
            # Second line - invalid in middle
            [[-104.23, 39.46], [181.0, 39.47], [-104.21, 39.48]],
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiLineString')
        # Should mention line 1 (second line, 0-indexed) and the invalid coordinate
        assert ('line 1' in str(exc_info.value).lower() or 'ring/line 1' in str(exc_info.value).lower()) and 'invalid' in str(exc_info.value).lower()

    def test_multipolygon_invalid_in_first_polygon(self):
        """Test that invalid coordinate in first polygon of MultiPolygon is caught."""
        coordinates = [
            # First polygon - has invalid coordinate
            [[[-104.26, 39.43], [181.0, 39.43], [-104.25, 39.44], [-104.26, 39.44], [-104.26, 39.43]]],
            # Second polygon - all valid
            [[[-104.20, 39.50], [-104.19, 39.50], [-104.19, 39.51], [-104.20, 39.51], [-104.20, 39.50]]]
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiPolygon')
        # Should mention polygon 0 (first polygon) and invalid coordinate
        assert 'polygon 0' in str(exc_info.value).lower() and 'invalid' in str(exc_info.value).lower()

    def test_multipolygon_invalid_in_second_polygon(self):
        """Test that invalid coordinate in second polygon of MultiPolygon is caught."""
        coordinates = [
            # First polygon - all valid
            [[[-104.26, 39.43], [-104.25, 39.43], [-104.25, 39.44], [-104.26, 39.44], [-104.26, 39.43]]],
            # Second polygon - has invalid coordinate
            [[[-104.20, 39.50], [181.0, 39.51], [-104.18, 39.52], [-104.20, 39.52], [-104.20, 39.50]]]
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiPolygon')
        assert 'polygon 1' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_multipolygon_invalid_deep_nested(self):
        """Test that invalid coordinate deep in MultiPolygon structure is caught."""
        coordinates = [
            # First polygon - all valid
            [[[-104.26, 39.43], [-104.25, 39.43], [-104.25, 39.44], [-104.26, 39.44], [-104.26, 39.43]]],
            # Second polygon with hole - all valid
            [
                [[-104.20, 39.50], [-104.15, 39.50], [-104.15, 39.55], [-104.20, 39.55], [-104.20, 39.50]],
                [[-104.19, 39.51], [-104.16, 39.51], [-104.16, 39.54], [-104.19, 39.54], [-104.19, 39.51]]
            ],
            # Third polygon - has invalid coordinate in 3rd point
            [[[-104.10, 39.60], [-104.09, 39.60], [-104.09, 91.0], [-104.10, 39.62], [-104.10, 39.60]]]
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiPolygon')
        assert 'polygon 2' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_large_linestring_invalid_coordinate_far_in(self):
        """Test that invalid coordinate is caught even far into a large LineString."""
        # Create a LineString with 100 valid points and 1 invalid at position 75
        coordinates = [[-104.0 + i * 0.01, 39.0 + i * 0.01] for i in range(100)]
        coordinates[75] = [181.0, 39.75]  # Invalid coordinate at index 75
        
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'LineString')
        assert 'index 75' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_polygon_with_none_in_middle(self):
        """Test that None coordinate in middle of polygon is caught."""
        coordinates = [[
            [-104.26, 39.43],  # Valid
            [-104.25, 39.43],  # Valid
            [None, 39.44],     # INVALID - None
            [-104.26, 39.44],  # Valid
            [-104.26, 39.43],  # Valid (closing)
        ]]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'Polygon')
        assert 'none' in str(exc_info.value).lower()

    def test_linestring_with_string_coordinate_in_middle(self):
        """Test that string coordinate in middle of LineString is caught."""
        coordinates = [
            [-104.26, 39.43],     # Valid
            [-104.25, 39.44],     # Valid
            ['invalid', 39.45],   # INVALID - string instead of number
            [-104.23, 39.46],     # Valid
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'LineString')
        # Error message should indicate the type issue
        assert 'must be a number' in str(exc_info.value).lower() or 'invalid' in str(exc_info.value).lower()

    def test_multipolygon_invalid_in_hole_of_third_polygon(self):
        """Test deeply nested case: invalid coordinate in hole of third polygon."""
        coordinates = [
            # First polygon - all valid
            [[[-104.26, 39.43], [-104.25, 39.43], [-104.25, 39.44], [-104.26, 39.44], [-104.26, 39.43]]],
            # Second polygon - all valid
            [[[-104.20, 39.50], [-104.19, 39.50], [-104.19, 39.51], [-104.20, 39.51], [-104.20, 39.50]]],
            # Third polygon with hole - invalid coordinate in the hole (second ring)
            [
                # Outer ring - all valid
                [[-104.15, 39.60], [-104.10, 39.60], [-104.10, 39.65], [-104.15, 39.65], [-104.15, 39.60]],
                # Inner ring (hole) - has invalid coordinate at position 2
                [[-104.14, 39.61], [-104.11, 39.61], [181.0, 39.64], [-104.14, 39.64], [-104.14, 39.61]]
            ]
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiPolygon')
        # Should mention both polygon 2 and ring 1
        error_msg = str(exc_info.value).lower()
        assert 'polygon 2' in error_msg and 'ring 1' in error_msg or 'bounds' in error_msg

    def test_polygon_with_infinity_in_middle(self):
        """Test that Infinity coordinate in middle of polygon is caught."""
        import math
        coordinates = [[
            [-104.26, 39.43],     # Valid
            [-104.25, 39.43],     # Valid
            [math.inf, 39.44],    # INVALID - Infinity
            [-104.26, 39.44],     # Valid
            [-104.26, 39.43],     # Valid (closing)
        ]]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'Polygon')
        assert 'infinity' in str(exc_info.value).lower()

    def test_multilinestring_with_three_lines_invalid_in_middle_line(self):
        """Test that invalid coordinate in middle line of 3-line MultiLineString is caught."""
        coordinates = [
            # First line - all valid
            [[-104.26, 39.43], [-104.25, 39.44], [-104.24, 39.45]],
            # Second line - has invalid coordinate at index 1
            [[-104.23, 39.46], [-104.22, -91.0], [-104.21, 39.48]],
            # Third line - all valid
            [[-104.20, 39.49], [-104.19, 39.50], [-104.18, 39.51]]
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiLineString')
        # Should mention line 1 (0-indexed) and point 1
        error_msg = str(exc_info.value).lower()
        assert ('line 1' in error_msg or 'ring/line 1' in error_msg) or 'bounds' in error_msg

    def test_very_large_polygon_invalid_near_end(self):
        """Test that invalid coordinate near the end of a very large polygon is caught."""
        # Create polygon with 200 points
        num_points = 200
        coordinates = [[[-104.0 + (i % 20) * 0.01, 39.0 + (i // 20) * 0.01] for i in range(num_points)]]
        # Close the polygon
        coordinates[0].append(coordinates[0][0])
        # Insert invalid coordinate at position 195 (near end)
        coordinates[0][195] = [-104.15, 91.0]
        
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'Polygon')
        assert 'point 195' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_multipoint_with_50_points_invalid_at_position_37(self):
        """Test that invalid coordinate at arbitrary position in large MultiPoint is caught."""
        # Create 50 valid points
        coordinates = [[-104.0 + i * 0.01, 39.0 + (i % 10) * 0.01] for i in range(50)]
        # Insert invalid coordinate at position 37
        coordinates[37] = [181.0, 39.07]
        
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiPoint')
        assert 'index 37' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_polygon_second_ring_invalid_coordinate(self):
        """Test that invalid coordinate in second ring (first hole) is caught."""
        coordinates = [
            # Outer ring - all valid
            [[-104.30, 39.40], [-104.10, 39.40], [-104.10, 39.70], [-104.30, 39.70], [-104.30, 39.40]],
            # First hole - has invalid coordinate at position 2
            [[-104.28, 39.42], [-104.27, 39.42], [-104.27, 91.0], [-104.28, 39.44], [-104.28, 39.42]],
            # Second hole - all valid
            [[-104.25, 39.50], [-104.23, 39.50], [-104.23, 39.52], [-104.25, 39.52], [-104.25, 39.50]]
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'Polygon')
        error_msg = str(exc_info.value).lower()
        assert ('ring/line 1' in error_msg or 'ring 1' in error_msg) or 'latitude' in error_msg

    def test_polygon_third_ring_invalid_coordinate(self):
        """Test that invalid coordinate in third ring (second hole) is caught."""
        coordinates = [
            # Outer ring - all valid
            [[-104.30, 39.40], [-104.10, 39.40], [-104.10, 39.70], [-104.30, 39.70], [-104.30, 39.40]],
            # First hole - all valid
            [[-104.28, 39.42], [-104.27, 39.42], [-104.27, 39.44], [-104.28, 39.44], [-104.28, 39.42]],
            # Second hole - has invalid coordinate
            [[-104.25, 39.50], [181.0, 39.50], [-104.23, 39.52], [-104.25, 39.52], [-104.25, 39.50]]
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'Polygon')
        error_msg = str(exc_info.value).lower()
        assert ('ring/line 2' in error_msg or 'ring 2' in error_msg) or 'longitude' in error_msg

    def test_linestring_all_valid_except_very_last(self):
        """Test that invalid coordinate at the very last position is caught."""
        coordinates = [
            [-104.26, 39.43],
            [-104.25, 39.44],
            [-104.24, 39.45],
            [-104.23, 39.46],
            [-104.22, 39.47],
            [181.0, 39.48],  # INVALID at very end
        ]
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'LineString')
        assert 'index 5' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()

    def test_multipolygon_five_polygons_invalid_in_fourth(self):
        """Test that invalid coordinate in 4th polygon of 5-polygon MultiPolygon is caught."""
        # Helper function to create a valid square polygon
        def make_polygon(base_lon, base_lat):
            return [[
                [base_lon, base_lat],
                [base_lon + 0.05, base_lat],
                [base_lon + 0.05, base_lat + 0.05],
                [base_lon, base_lat + 0.05],
                [base_lon, base_lat]
            ]]
        
        coordinates = [
            make_polygon(-104.30, 39.40),  # Polygon 0 - valid
            make_polygon(-104.20, 39.40),  # Polygon 1 - valid
            make_polygon(-104.10, 39.40),  # Polygon 2 - valid
            # Polygon 3 - has invalid coordinate at position 2
            [[
                [-104.00, 39.40],
                [-103.95, 39.40],
                [-103.95, 91.0],  # INVALID
                [-104.00, 39.45],
                [-104.00, 39.40]
            ]],
            make_polygon(-103.90, 39.40),  # Polygon 4 - valid
        ]
        
        with pytest.raises(CoordinateValidationError) as exc_info:
            validate_coordinates_for_geometry_type(coordinates, 'MultiPolygon')
        assert 'polygon 3' in str(exc_info.value).lower() or 'bounds' in str(exc_info.value).lower()


