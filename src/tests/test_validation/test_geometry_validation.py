"""
Tests for geometry validation.
"""
import pytest
from geo_lib.validation.geometry_validation import (
    validate_geometry,
    validate_coordinates_values,
    validate_feature_geometry,
    normalize_and_validate_feature_update,
    GeometryValidationError,
    VALID_GEOMETRY_TYPES,
)


class TestGeometryValidation:
    """Test geometry validation functions."""

    def test_valid_point(self):
        """Test validation of a valid Point."""
        geometry = {
            'type': 'Point',
            'coordinates': [-122.4194, 37.7749]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

    def test_valid_linestring(self):
        """Test validation of a valid LineString."""
        geometry = {
            'type': 'LineString',
            'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

    def test_valid_polygon(self):
        """Test validation of a valid Polygon."""
        geometry = {
            'type': 'Polygon',
            'coordinates': [[[-122.4194, 37.7749], [-122.4094, 37.7749],
                            [-122.4094, 37.7849], [-122.4194, 37.7849],
                            [-122.4194, 37.7749]]]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

    def test_valid_geometry_collection(self):
        """Test validation of a valid GeometryCollection."""
        geometry = {
            'type': 'GeometryCollection',
            'geometries': [
                {
                    'type': 'Point',
                    'coordinates': [-122.4194, 37.7749]
                },
                {
                    'type': 'LineString',
                    'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]
                }
            ]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

    def test_invalid_geometry_type(self):
        """Test that invalid geometry types raise errors."""
        geometry = {
            'type': 'InvalidType',
            'coordinates': [-122.4194, 37.7749]
        }
        with pytest.raises(GeometryValidationError):
            validate_geometry(geometry)

    def test_missing_type(self):
        """Test that missing type raises error."""
        geometry = {
            'coordinates': [-122.4194, 37.7749]
        }
        with pytest.raises(GeometryValidationError):
            validate_geometry(geometry)

    def test_geometry_collection_missing_geometries(self):
        """Test that GeometryCollection without geometries raises error."""
        geometry = {
            'type': 'GeometryCollection'
        }
        with pytest.raises(GeometryValidationError):
            validate_geometry(geometry)

    def test_point_missing_coordinates(self):
        """Test that Point without coordinates raises error."""
        geometry = {
            'type': 'Point'
        }
        with pytest.raises(GeometryValidationError):
            validate_geometry(geometry)

    def test_coordinate_bounds_latitude_too_high(self):
        """Test that latitude > 90 raises error."""
        geometry = {
            'type': 'Point',
            'coordinates': [-122.4194, 91.0]
        }
        validate_geometry(geometry)
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry)

    def test_coordinate_bounds_latitude_too_low(self):
        """Test that latitude < -90 raises error."""
        geometry = {
            'type': 'Point',
            'coordinates': [-122.4194, -91.0]
        }
        validate_geometry(geometry)
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry)

    def test_coordinate_bounds_longitude_too_high(self):
        """Test that longitude > 180 raises error."""
        geometry = {
            'type': 'Point',
            'coordinates': [181.0, 37.7749]
        }
        validate_geometry(geometry)
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry)

    def test_coordinate_bounds_longitude_too_low(self):
        """Test that longitude < -180 raises error."""
        geometry = {
            'type': 'Point',
            'coordinates': [-181.0, 37.7749]
        }
        validate_geometry(geometry)
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry)

    def test_coordinate_bounds_at_limits(self):
        """Test that coordinates at valid limits pass."""
        geometry = {
            'type': 'Point',
            'coordinates': [180.0, 90.0]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

        geometry = {
            'type': 'Point',
            'coordinates': [-180.0, -90.0]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

    def test_none_coordinate_value(self):
        """Test that None coordinate values raise error."""
        geometry = {
            'type': 'Point',
            'coordinates': [None, 37.7749]
        }
        validate_geometry(geometry)
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry)

    def test_invalid_coordinate_type(self):
        """Test that non-numeric coordinates raise error."""
        geometry = {
            'type': 'Point',
            'coordinates': ['invalid', 37.7749]
        }
        validate_geometry(geometry)
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry)

    def test_invalid_point_structure(self):
        """Test that invalid point structure raises error."""
        geometry = {
            'type': 'Point',
            'coordinates': [-122.4194]  # Missing latitude
        }
        validate_geometry(geometry)
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry)

    def test_geometry_collection_coordinate_validation(self):
        """Test coordinate validation for GeometryCollection."""
        geometry = {
            'type': 'GeometryCollection',
            'geometries': [
                {
                    'type': 'Point',
                    'coordinates': [-122.4194, 91.0]  # Invalid latitude
                }
            ]
        }
        validate_geometry(geometry)
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry)

    def test_feature_geometry_validation(self):
        """Test feature geometry validation."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test'
            }
        }
        validate_feature_geometry(feature)

    def test_feature_missing_geometry(self):
        """Test that feature without geometry raises error."""
        feature = {
            'type': 'Feature',
            'properties': {
                'name': 'Test'
            }
        }
        with pytest.raises(GeometryValidationError):
            validate_feature_geometry(feature)

    def test_feature_invalid_type(self):
        """Test that non-Feature type raises error."""
        feature = {
            'type': 'FeatureCollection',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            }
        }
        with pytest.raises(GeometryValidationError):
            validate_feature_geometry(feature)

    def test_normalize_feature_update_with_feature(self):
        """Test normalize_and_validate_feature_update with Feature object."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test'
            }
        }
        original_properties = {'name': 'Original'}
        result = normalize_and_validate_feature_update(feature, original_properties)
        assert result['type'] == 'Feature'
        assert result['geometry']['type'] == 'Point'

    def test_normalize_feature_update_with_geometry(self):
        """Test normalize_and_validate_feature_update with geometry object."""
        geometry = {
            'type': 'Point',
            'coordinates': [-122.4194, 37.7749]
        }
        original_properties = {'name': 'Original', 'tags': ['test']}
        result = normalize_and_validate_feature_update(geometry, original_properties)
        assert result['type'] == 'Feature'
        assert result['geometry']['type'] == 'Point'
        assert result['properties'] == original_properties

    def test_normalize_feature_update_invalid(self):
        """Test normalize_and_validate_feature_update with invalid input."""
        invalid = {
            'type': 'InvalidType'
        }
        original_properties = {'name': 'Original'}
        with pytest.raises(GeometryValidationError):
            normalize_and_validate_feature_update(invalid, original_properties)

    def test_multipoint_validation(self):
        """Test MultiPoint validation."""
        geometry = {
            'type': 'MultiPoint',
            'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

    def test_multilinestring_validation(self):
        """Test MultiLineString validation."""
        geometry = {
            'type': 'MultiLineString',
            'coordinates': [[[-122.4194, 37.7749], [-122.4094, 37.7849]]]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

    def test_multipolygon_validation(self):
        """Test MultiPolygon validation."""
        geometry = {
            'type': 'MultiPolygon',
            'coordinates': [[[[-122.4194, 37.7749], [-122.4094, 37.7749],
                            [-122.4094, 37.7849], [-122.4194, 37.7849],
                            [-122.4194, 37.7749]]]]
        }
        validate_geometry(geometry)
        validate_coordinates_values(geometry)

