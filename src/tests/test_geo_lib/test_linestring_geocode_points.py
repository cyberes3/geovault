"""
Unit tests for linestring geocode points: equal-spacing helper and get_representative_points.
"""
import pytest
from unittest.mock import patch

from geo_lib.processing.tagging.modules.reverse_geocoding import (
    _sample_points_along_line,
    get_representative_points,
)
from geo_lib.spatial.haversine import haversine_distance_meters
from geo_lib.types.feature import (
    LineStringFeature,
    MultiLineStringFeature,
    PointFeature,
)


class TestSamplePointsAlongLine:
    """Unit tests for _sample_points_along_line."""

    def test_empty_coords_returns_empty(self):
        assert _sample_points_along_line([], 4) == []

    def test_single_vertex_returns_one_point(self):
        result = _sample_points_along_line([[10.0, 20.0]], 4)
        assert result == [(20.0, 10.0)]  # (lat, lon)

    def test_two_vertices_n1_returns_midpoint(self):
        # A -> B; one point should be interpolated midpoint
        coords = [[0.0, 0.0], [2.0, 0.0]]  # lon, lat - same lat, so simple
        result = _sample_points_along_line(coords, 1)
        assert len(result) == 1
        lat, lon = result[0]
        assert lat == 0.0
        assert 0.0 <= lon <= 2.0

    def test_three_vertices_n1_returns_midpoint_by_distance(self):
        coords = [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0]]
        result = _sample_points_along_line(coords, 1)
        assert len(result) == 1
        assert result[0][0] == 0.0
        assert result[0][1] == 1.0  # midpoint along line

    def test_three_vertices_n3_returns_start_mid_end(self):
        coords = [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0]]
        result = _sample_points_along_line(coords, 3)
        assert len(result) == 3
        assert result[0] == (0.0, 0.0)
        assert result[2] == (0.0, 2.0)
        assert result[1][0] == 0.0 and result[1][1] == 1.0

    def test_n_greater_than_vertices(self):
        coords = [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0]]
        result = _sample_points_along_line(coords, 5)
        assert len(result) == 5
        # First and last should be endpoints
        assert result[0] == (0.0, 0.0)
        assert result[4] == (0.0, 2.0)

    def test_zero_length_line_returns_single_point(self):
        coords = [[1.0, 2.0], [1.0, 2.0]]
        result = _sample_points_along_line(coords, 4)
        assert len(result) == 1
        assert result[0] == (2.0, 1.0)

    def test_coords_with_elevation_uses_first_two_elements(self):
        coords = [[10.0, 20.0, 100.0], [11.0, 21.0, 200.0]]
        result = _sample_points_along_line(coords, 2)
        assert len(result) == 2
        assert result[0] == (20.0, 10.0)
        assert result[1] == (21.0, 11.0)

    def test_target_distance_exactly_zero_and_l(self):
        coords = [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0]]
        result = _sample_points_along_line(coords, 3)
        assert result[0] == (0.0, 0.0)
        assert result[2] == (0.0, 2.0)

    def test_multilinestring_flattening_two_segments(self):
        # Simulate flattened [A,B] + [C,D]
        coords = [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0], [3.0, 0.0]]
        result = _sample_points_along_line(coords, 2)
        assert len(result) == 2
        assert result[0] == (0.0, 0.0)
        assert result[1] == (0.0, 3.0)

    def test_equal_spacing_distances_between_consecutive_points(self):
        """Consecutive sample points are equally spaced by arc-length (haversine)."""
        # Line along equator: 0,0 -> 0.001,0 -> 0.002,0 -> 0.003,0 (lon, lat in degrees)
        coords = [[0.0, 0.0], [0.001, 0.0], [0.002, 0.0], [0.003, 0.0]]
        result = _sample_points_along_line(coords, 5)
        assert len(result) == 5
        # result is (lat, lon) per point
        gaps = []
        for i in range(len(result) - 1):
            lat1, lon1 = result[i]
            lat2, lon2 = result[i + 1]
            d = haversine_distance_meters(lat1, lon1, lat2, lon2)
            gaps.append(d)
        expected_gap = sum(gaps) / len(gaps)
        for g in gaps:
            assert abs(g - expected_gap) / (expected_gap + 1e-10) < 0.01, (
                f"Gaps should be equal: {gaps}, expected ~{expected_gap}"
            )

    def test_equal_spacing_total_distance_matches_line_length(self):
        """Sum of distances between consecutive samples equals total line length."""
        coords = [[-105.0, 40.0], [-104.99, 40.0], [-104.98, 40.01]]
        result = _sample_points_along_line(coords, 4)
        assert len(result) == 4
        total_from_samples = 0.0
        for i in range(len(result) - 1):
            lat1, lon1 = result[i]
            lat2, lon2 = result[i + 1]
            total_from_samples += haversine_distance_meters(lat1, lon1, lat2, lon2)
        total_line = 0.0
        for i in range(len(coords) - 1):
            lat1, lon1 = coords[i][1], coords[i][0]
            lat2, lon2 = coords[i + 1][1], coords[i + 1][0]
            total_line += haversine_distance_meters(lat1, lon1, lat2, lon2)
        # Sum of gaps should equal total line length (first at 0, last at L)
        assert abs(total_from_samples - total_line) / (total_line + 1e-10) < 0.02

    def test_equal_spacing_unequal_segment_lengths(self):
        """Equal spacing holds even when polyline segments have different lengths."""
        # Short segment then long segment: 0,0 -> 0.0001,0 -> 0.001,0 (lon, lat)
        coords = [[0.0, 0.0], [0.0001, 0.0], [0.001, 0.0]]
        result = _sample_points_along_line(coords, 4)
        assert len(result) == 4
        gaps = []
        for i in range(len(result) - 1):
            lat1, lon1 = result[i]
            lat2, lon2 = result[i + 1]
            gaps.append(haversine_distance_meters(lat1, lon1, lat2, lon2))
        expected_gap = sum(gaps) / len(gaps)
        for g in gaps:
            assert abs(g - expected_gap) / (expected_gap + 1e-10) < 0.02


class TestGetRepresentativePoints:
    """Unit tests for get_representative_points with patched setting."""

    def test_point_returns_single_point(self):
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.0, 37.0]},
            properties={'geojson_hash': 'x'},
        )
        with patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=4):
            result = get_representative_points(feature)
        assert result == [(37.0, -122.0)]

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=4)
    def test_linestring_default_n4_returns_four_points(self, mock_setting):
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0], [3.0, 0.0]],
            },
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert len(result) == 4
        assert result[0] == (0.0, 0.0)
        assert result[3] == (0.0, 3.0)

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=3)
    def test_linestring_n3_returns_three_points(self, mock_setting):
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0]],
            },
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert len(result) == 3
        assert result[0] == (0.0, 0.0)
        assert result[2] == (0.0, 2.0)

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=2)
    def test_multilinestring_n2_returns_two_points(self, mock_setting):
        feature = MultiLineStringFeature(
            type='Feature',
            geometry={
                'type': 'MultiLineString',
                'coordinates': [
                    [[0.0, 0.0], [1.0, 0.0]],
                    [[2.0, 0.0], [3.0, 0.0]],
                ],
            },
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert len(result) == 2
        assert result[0] == (0.0, 0.0)
        assert result[1] == (0.0, 3.0)

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=0)
    def test_clamping_zero_effective_n1(self, mock_setting):
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[0.0, 0.0], [2.0, 0.0]],
            },
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert len(result) == 1

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=200)
    def test_clamping_200_effective_100(self, mock_setting):
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[0.0, 0.0], [1.0, 0.0]],
            },
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert len(result) == 100

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=-1)
    def test_negative_config_effective_n1(self, mock_setting):
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[0.0, 0.0], [1.0, 0.0]],
            },
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert len(result) == 1

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=4)
    def test_empty_linestring_returns_empty(self, mock_setting):
        feature = LineStringFeature(
            type='Feature',
            geometry={'type': 'LineString', 'coordinates': []},
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert result == []

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=4)
    def test_linestring_one_vertex_returns_one_point(self, mock_setting):
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[10.0, 20.0]],
            },
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert result == [(20.0, 10.0)]

    @patch('geo_lib.processing.tagging.modules.reverse_geocoding.get_setting', return_value=None)
    def test_none_setting_uses_default_four(self, mock_setting):
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0], [3.0, 0.0]],
            },
            properties={'geojson_hash': 'x'},
        )
        result = get_representative_points(feature)
        assert len(result) == 4
