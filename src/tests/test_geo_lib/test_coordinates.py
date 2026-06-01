"""
Unit tests for coordinate normalization and geometry matching (duplicate detection).
"""
from geo_lib.processing.duplicate_detection.constants import COORDINATE_TOLERANCE
from geo_lib.spatial.coordinates import (
    coordinates_match,
    geometries_match,
    normalize_coordinates,
)


class TestGeometriesMatch:
    def test_empty_both_match(self):
        assert geometries_match([], []) is True

    def test_one_empty_no_match(self):
        assert geometries_match([], [[-105.0, 38.0]]) is False
        assert geometries_match([[-105.0, 38.0]], []) is False

    def test_point_within_tolerance(self):
        low_precision = [-105.64053, 38.79543]
        high_precision = [-105.64053344726562, 38.79542922973633]
        assert geometries_match(low_precision, high_precision) is True

    def test_point_outside_tolerance(self):
        a = [-122.4194, 37.7749]
        b = [-122.5194, 37.8749]
        assert geometries_match(a, b) is False

    def test_point_2d_vs_3d_same_position_matches(self):
        assert geometries_match([-105.0, 38.0], [-105.0, 38.0, 0.0]) is True
        assert geometries_match([-105.0, 38.0], [-105.0, 38.0, 2500.0]) is True

    def test_linestring_2d_vs_3d_same_path_matches(self):
        path_2d = [
            [-105.64053, 38.79543],
            [-105.64060, 38.79550],
        ]
        path_3d = [
            [-105.64053, 38.79543, 100.0],
            [-105.64060, 38.79550, 105.0],
        ]
        assert geometries_match(path_2d, path_3d) is True

    def test_linestring_identical(self):
        coords = [
            [-105.64053, 38.79543],
            [-105.64060, 38.79550],
            [-105.64070, 38.79560],
        ]
        assert geometries_match(coords, list(coords)) is True

    def test_linestring_different_vertex_count(self):
        shared = [
            [-105.64053, 38.79543],
            [-105.64060, 38.79550],
        ]
        longer = shared + [[-105.64100, 38.79600]]
        assert geometries_match(shared, longer) is False

    def test_linestring_per_vertex_precision(self):
        low = [
            [-105.64053, 38.79543],
            [-105.64060, 38.79550],
        ]
        high = [
            [-105.64053344726562, 38.79542922973633],
            [-105.64060000000001, 38.79550000000001],
        ]
        assert geometries_match(low, high) is True

    def test_polygon_matching_rings(self):
        ring = [
            [-105.0, 38.0],
            [-105.1, 38.0],
            [-105.1, 38.1],
            [-105.0, 38.1],
            [-105.0, 38.0],
        ]
        assert geometries_match([ring], [list(ring)]) is True

    def test_structure_mismatch(self):
        point = [-105.0, 38.0]
        line = [[-105.0, 38.0], [-105.1, 38.1]]
        assert geometries_match(point, line) is False


class TestNormalizeCoordinates:
    def test_rounds_to_six_decimals(self):
        assert normalize_coordinates([-105.64053344726562, 38.79542922973633]) == [
            -105.640533,
            38.795429,
        ]

    def test_nested_structure_preserved(self):
        coords = [[-105.0, 38.0], [-105.1, 38.1]]
        normalized = normalize_coordinates(coords)
        assert len(normalized) == 2
        assert len(normalized[0]) == 2


class TestCoordinatesMatch:
    def test_delegates_to_geometries_match(self):
        a = [-105.64053, 38.79543]
        b = [-105.64053344726562, 38.79542922973633]
        assert coordinates_match(a, b) == geometries_match(a, b, COORDINATE_TOLERANCE)
