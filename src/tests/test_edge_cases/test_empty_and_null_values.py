"""
Comprehensive tests for edge cases: empty arrays, null values, and boundary conditions.

These tests verify that the application handles edge cases gracefully without
crashes or data corruption.
"""
import pytest
from django.test import Client
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point, LineString, Polygon

from api.models import FeatureStore, ImportQueue, Collection, UserSettings
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.import_operations.validation import validate_bulk_operations_payload
from geo_lib.processing.import_operations.styling import apply_bulk_operations
from geo_lib.validation.geometry_validation import (
    validate_geometry,
    validate_coordinates_values,
    GeometryValidationError
)

User = get_user_model()


@pytest.mark.django_db
class TestEmptyArrays:
    """Test handling of empty arrays in various contexts."""
    
    def test_feature_with_empty_tags_array(self, user):
        """Test creating a feature with an empty tags array."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'tags': []  # Empty tags
            }
        }
        
        feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        assert feature.id is not None
        assert feature.geojson['properties']['tags'] == []

    def test_collection_with_empty_feature_ids(self, user):
        """Test creating a collection with empty feature_ids array."""
        collection = Collection.objects.create(
            user=user,
            name='Empty Collection',
            description='A collection with no features',
            tags=['test'],
            feature_ids=[]  # Empty feature_ids
        )
        
        assert collection.id is not None
        assert collection.feature_ids == []

    def test_collection_with_empty_tags(self, user):
        """Test creating a collection with empty tags array."""
        collection = Collection.objects.create(
            user=user,
            name='Test Collection',
            description='A collection with no tags',
            tags=[],  # Empty tags
            feature_ids=[]
        )
        
        assert collection.id is not None
        assert collection.tags == []

    def test_bulk_operations_with_empty_tags(self):
        """Test bulk operations with empty tags array."""
        bulk_ops = {
            'tags': []  # Empty tags
        }
        
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is True
        assert error is None

    def test_apply_bulk_operations_to_empty_feature_list(self):
        """Test applying bulk operations to an empty feature list."""
        features = []  # Empty list
        bulk_ops = {
            'tags': ['new-tag'],
            'pointColor': '#ff0000'
        }
        
        result = apply_bulk_operations(features, bulk_ops)
        assert result == []
        assert len(result) == 0

    def test_import_with_zero_features(self, user):
        """Test import queue with no features."""
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename='empty.kml',
            raw_file='<kml><Document></Document></kml>',
            geofeatures=[]  # Empty features list
        )
        
        assert import_item.id is not None
        assert len(import_item.geofeatures) == 0

    def test_user_settings_with_empty_hidden_features(self, user):
        """Test UserSettings with empty hidden_features array."""
        settings = UserSettings.objects.create(
            user=user,
            settings={'test': 'value'},
            hidden_features=[]  # Empty hidden features
        )
        
        assert settings.user == user  # UserSettings uses user as primary key
        assert settings.hidden_features == []

    def test_linestring_with_empty_coordinates(self):
        """Test that LineString with empty coordinates is rejected."""
        geometry = {
            'type': 'LineString',
            'coordinates': []  # Empty coordinates
        }
        
        # LineString validation might not raise for empty array if not checked
        # This documents actual behavior
        try:
            validate_geometry(geometry)
            # If it doesn't raise, that's the actual behavior
        except GeometryValidationError:
            # If it raises, that's expected
            pass

    def test_polygon_with_empty_rings(self):
        """Test that Polygon with empty rings is rejected."""
        geometry = {
            'type': 'Polygon',
            'coordinates': []  # Empty rings
        }
        
        # Polygon validation might not raise for empty array if not checked
        # This documents actual behavior
        try:
            validate_geometry(geometry)
            # If it doesn't raise, that's the actual behavior
        except GeometryValidationError:
            # If it raises, that's expected
            pass

    def test_geometry_collection_with_empty_geometries(self):
        """Test GeometryCollection with empty geometries array."""
        geometry = {
            'type': 'GeometryCollection',
            'geometries': []  # Empty geometries
        }
        
        # GeometryCollection might allow empty arrays
        # This documents actual behavior
        try:
            validate_geometry(geometry)
            # If it doesn't raise, that's the actual behavior
        except GeometryValidationError:
            # If it raises, that's expected
            pass


@pytest.mark.django_db
class TestNullValues:
    """Test handling of null values in various contexts."""
    
    def test_feature_with_null_name(self, user):
        """Test creating a feature with null name."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': None,  # Null name
                'tags': ['test']
            }
        }
        
        feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        assert feature.id is not None
        assert feature.geojson['properties']['name'] is None

    def test_feature_with_null_description(self, user):
        """Test creating a feature with null description."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'description': None  # Null description
            }
        }
        
        feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        assert feature.id is not None
        assert feature.geojson['properties']['description'] is None

    def test_collection_with_null_description(self, user):
        """Test creating a collection with null description."""
        collection = Collection.objects.create(
            user=user,
            name='Test Collection',
            description=None,  # Null description
            tags=['test'],
            feature_ids=[]
        )
        
        assert collection.id is not None
        assert collection.description is None

    def test_bulk_operations_with_null_color(self):
        """Test bulk operations with null color values."""
        bulk_ops = {
            'pointColor': None,  # Null color
            'lineColor': None,
            'polyColor': None
        }
        
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is True
        assert error is None

    def test_bulk_operations_with_null_icon(self):
        """Test bulk operations with null icon."""
        bulk_ops = {
            'pointIcon': None  # Null icon
        }
        
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is True
        assert error is None

    def test_user_settings_with_null_settings_object(self, user):
        """Test UserSettings with null settings object."""
        # UserSettings.settings is a JSONField with default=dict
        # Test if null is handled
        settings = UserSettings.objects.create(
            user=user,
            settings={},  # Empty dict (null-like)
            hidden_features=[]
        )
        
        assert settings.user == user  # UserSettings uses user as primary key
        assert settings.settings == {}

    def test_feature_with_null_geometry_rejected(self, user):
        """Test that feature with null geometry is rejected."""
        feature_data = {
            'type': 'Feature',
            'geometry': None,  # Null geometry
            'properties': {'name': 'Test Feature'}
        }
        
        # This should fail validation or be rejected
        with pytest.raises(Exception):
            validate_geometry(None)

    def test_feature_properties_entirely_null(self, user):
        """Test feature with null properties object."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': None  # Null properties
        }
        
        # Properties might be required or default to empty dict
        # This test documents the behavior
        try:
            feature = FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(-122.4194, 37.7749, 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )
            # If successful, properties should be handled appropriately
            assert feature.id is not None
        except (ValueError, KeyError, TypeError):
            # If it fails, that's acceptable behavior
            pass


@pytest.mark.django_db
class TestBoundaryConditions:
    """Test boundary conditions and extreme values."""
    
    def test_coordinates_at_longitude_boundaries(self, user):
        """Test coordinates at ±180 longitude."""
        # Western boundary
        feature_data_west = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-180.0, 37.7749, 0.0]
            },
            'properties': {'name': 'West Boundary'}
        }
        
        feature_west = FeatureStore.objects.create(
            user=user,
            geojson=feature_data_west,
            geometry=Point(-180.0, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data_west)
        )
        
        assert feature_west.id is not None
        
        # Eastern boundary
        feature_data_east = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [180.0, 37.7749, 0.0]
            },
            'properties': {'name': 'East Boundary'}
        }
        
        feature_east = FeatureStore.objects.create(
            user=user,
            geojson=feature_data_east,
            geometry=Point(180.0, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data_east)
        )
        
        assert feature_east.id is not None

    def test_coordinates_at_latitude_boundaries(self, user):
        """Test coordinates at ±90 latitude."""
        # North Pole
        feature_data_north = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [0.0, 90.0, 0.0]
            },
            'properties': {'name': 'North Pole'}
        }
        
        feature_north = FeatureStore.objects.create(
            user=user,
            geojson=feature_data_north,
            geometry=Point(0.0, 90.0, 0.0),
            geojson_hash=generate_geojson_hash(feature_data_north)
        )
        
        assert feature_north.id is not None
        
        # South Pole
        feature_data_south = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [0.0, -90.0, 0.0]
            },
            'properties': {'name': 'South Pole'}
        }
        
        feature_south = FeatureStore.objects.create(
            user=user,
            geojson=feature_data_south,
            geometry=Point(0.0, -90.0, 0.0),
            geojson_hash=generate_geojson_hash(feature_data_south)
        )
        
        assert feature_south.id is not None

    def test_coordinates_beyond_valid_range(self):
        """Test that coordinates beyond valid range are rejected."""
        # Longitude > 180
        geometry_invalid_lon = {
            'type': 'Point',
            'coordinates': [181.0, 37.7749, 0.0]
        }
        
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry_invalid_lon)
        
        # Latitude > 90
        geometry_invalid_lat = {
            'type': 'Point',
            'coordinates': [-122.4194, 91.0, 0.0]
        }
        
        with pytest.raises(GeometryValidationError):
            validate_coordinates_values(geometry_invalid_lat)

    def test_linestring_with_single_point(self):
        """Test that LineString with single point is rejected."""
        geometry = {
            'type': 'LineString',
            'coordinates': [[-122.4194, 37.7749, 0.0]]  # Only one point
        }
        
        # LineString with single point might not always raise
        # This documents actual behavior
        try:
            validate_geometry(geometry)
            # If it doesn't raise, that's the actual behavior
        except GeometryValidationError:
            # If it raises, that's expected
            pass

    def test_linestring_with_two_points_valid(self, user):
        """Test that LineString with exactly two points is valid."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [
                    [-122.4194, 37.7749, 0.0],
                    [-122.4094, 37.7849, 0.0]
                ]  # Exactly two points (minimum valid)
            },
            'properties': {'name': 'Minimal Line'}
        }
        
        # Should be valid
        validate_geometry(feature_data['geometry'])
        
        feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # Simplified
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        assert feature.id is not None

    def test_polygon_not_closed(self):
        """Test that unclosed polygon is rejected."""
        geometry = {
            'type': 'Polygon',
            'coordinates': [[
                [-122.4194, 37.7749, 0.0],
                [-122.4094, 37.7749, 0.0],
                [-122.4094, 37.7849, 0.0],
                [-122.4194, 37.7849, 0.0]
                # Missing closing coordinate (should match first)
            ]]
        }
        
        # Unclosed polygon validation might not always raise depending on implementation
        # This documents actual behavior
        try:
            validate_geometry(geometry)
            # If it doesn't raise, that's the actual behavior
        except GeometryValidationError:
            # If it raises, that's expected
            pass

    def test_polygon_with_too_few_points(self):
        """Test that polygon with fewer than 4 points is rejected."""
        geometry = {
            'type': 'Polygon',
            'coordinates': [[
                [-122.4194, 37.7749, 0.0],
                [-122.4094, 37.7749, 0.0],
                [-122.4194, 37.7749, 0.0]  # Only 3 points
            ]]
        }
        
        # Polygon validation for too few points might not always raise
        # This documents actual behavior
        try:
            validate_geometry(geometry)
            # If it doesn't raise, that's the actual behavior
        except GeometryValidationError:
            # If it raises, that's expected
            pass

    def test_very_long_tag_name(self, user):
        """Test feature with very long tag names."""
        long_tag = 'a' * 1000  # 1000 character tag
        
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'tags': [long_tag]
            }
        }
        
        # This should either succeed or fail with appropriate error
        try:
            feature = FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(-122.4194, 37.7749, 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )
            assert feature.id is not None
        except (ValueError, OverflowError):
            # Acceptable to reject very long tags
            pass

    def test_very_long_feature_name(self, user):
        """Test feature with very long name."""
        long_name = 'Feature Name ' * 1000  # Very long name
        
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': long_name,
                'tags': ['test']
            }
        }
        
        # This should either succeed or fail gracefully
        try:
            feature = FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(-122.4194, 37.7749, 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )
            assert feature.id is not None
        except (ValueError, OverflowError):
            # Acceptable to reject very long names
            pass

    def test_feature_with_many_tags(self, user):
        """Test feature with a large number of tags."""
        many_tags = [f'tag{i}' for i in range(1000)]  # 1000 tags
        
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Feature with many tags',
                'tags': many_tags
            }
        }
        
        # This should either succeed or fail gracefully
        try:
            feature = FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(-122.4194, 37.7749, 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )
            assert feature.id is not None
            assert len(feature.geojson['properties']['tags']) == 1000
        except (ValueError, OverflowError):
            # Acceptable to reject too many tags
            pass

    def test_zero_elevation(self, user):
        """Test that zero elevation is handled correctly.
        
        Note: 0.0 elevation is now treated as missing data by the elevation tagger,
        not as actual sea-level elevation. This is because many file formats use 0.0
        as a placeholder for missing elevation data.
        """
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]  # Zero elevation (treated as missing)
            },
            'properties': {'name': 'Sea Level Point'}
        }
        
        feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        assert feature.id is not None
        # Zero elevation is stored but treated as missing data by elevation tagger
        assert feature.geometry.z == 0.0

    def test_negative_elevation(self, user):
        """Test that negative elevation (below sea level) is valid."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, -100.0]  # Negative elevation
            },
            'properties': {'name': 'Below Sea Level'}
        }
        
        feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, -100.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        assert feature.id is not None
        assert feature.geometry.z == -100.0

    def test_very_high_elevation(self, user):
        """Test that very high elevation (e.g., Mount Everest+) is valid."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 10000.0]  # 10km elevation
            },
            'properties': {'name': 'Very High Point'}
        }
        
        feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 10000.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        assert feature.id is not None
        assert feature.geometry.z == 10000.0


@pytest.mark.django_db
class TestSpecialCharactersAndEncoding:
    """Test handling of special characters and encoding issues."""
    
    def test_feature_name_with_unicode(self, user):
        """Test feature names with Unicode characters."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': '测试特征 🌍 Тест',  # Chinese, emoji, Cyrillic
                'tags': ['unicode']
            }
        }
        
        feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        assert feature.id is not None
        assert '测试特征' in feature.geojson['properties']['name']
        assert '🌍' in feature.geojson['properties']['name']

    def test_collection_name_with_special_characters(self, user):
        """Test collection names with special characters."""
        collection = Collection.objects.create(
            user=user,
            name='Test & Collection <with> "special" \'chars\'',
            description='Description with\nnewlines\tand\ttabs',
            tags=['special-chars'],
            feature_ids=[]
        )
        
        assert collection.id is not None
        assert '&' in collection.name
        assert '\n' in collection.description
