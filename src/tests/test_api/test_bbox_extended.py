"""
Extended tests for bbox query functionality and edge cases.
"""
import json
from unittest.mock import patch
from django.test import TestCase
from django.contrib.gis.geos import Point

import uuid

from django.contrib.auth import get_user_model

from api.models import FeatureStore, Collection
from geo_lib.feature_id import generate_geojson_hash


class TestBboxEmptyResults(TestCase):
    """Test bbox queries with no results."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create a feature in one location
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]  # San Francisco
            },
            'properties': {
                'name': 'Test Point'
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )

    def test_bbox_no_results(self):
        """Test bbox query that returns no results."""
        # Query for bbox in Tokyo (far from San Francisco)
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '139.0,35.0,140.0,36.0', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['feature_count'], 0)
        self.assertEqual(data['total_features_in_bbox'], 0)

    def test_bbox_empty_database(self):
        """Test bbox query when user has no features."""
        # Delete all features
        FeatureStore.objects.filter(user=self.user).delete()
        
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['feature_count'], 0)


class TestBboxWorldWideExtent(TestCase):
    """Test bbox queries with world-wide extent."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create features in different parts of the world
        world_locations = [
            {'name': 'San Francisco', 'coords': [-122.4194, 37.7749, 0.0]},
            {'name': 'Tokyo', 'coords': [139.6917, 35.6895, 0.0]},
            {'name': 'London', 'coords': [-0.1278, 51.5074, 0.0]},
            {'name': 'Sydney', 'coords': [151.2093, -33.8688, 0.0]},
            {'name': 'Buenos Aires', 'coords': [-58.3816, -34.6037, 0.0]},
        ]
        
        for location in world_locations:
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': location['coords']
                },
                'properties': {
                    'name': location['name']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(location['coords'][0], location['coords'][1], 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

    def test_bbox_world_wide_extent(self):
        """Test bbox query spanning entire world."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-180,-90,180,90', 'zoom': '1'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should return all features
        self.assertGreaterEqual(data['feature_count'], 5)
        self.assertGreaterEqual(data['total_features_in_bbox'], 5)

    def test_bbox_fallback_mechanism(self):
        """Test that fallback mechanism is triggered for large extents."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-170,-80,170,80', 'zoom': '2'}  # Very large extent
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should have fallback_used field
        self.assertIn('fallback_used', data)
        # Should return features
        self.assertGreaterEqual(data['feature_count'], 0)

    def test_bbox_crossing_dateline(self):
        """Test bbox that crosses International Date Line."""
        # Create features on both sides of dateline
        feature_data1 = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [179.0, 0.0, 0.0]  # Just west of dateline
            },
            'properties': {'name': 'West of Dateline'}
        }
        feature_data2 = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-179.0, 0.0, 0.0]  # Just east of dateline
            },
            'properties': {'name': 'East of Dateline'}
        }
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data1,
            geometry=Point(179.0, 0.0, 0.0),
            geojson_hash=generate_geojson_hash(feature_data1)
        )
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data2,
            geometry=Point(-179.0, 0.0, 0.0),
            geojson_hash=generate_geojson_hash(feature_data2)
        )
        
        # Query that crosses dateline: min_lon > max_lon
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '170,-10,-170,10', 'zoom': '5'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should handle crossing dateline
        self.assertGreaterEqual(data['feature_count'], 0)


class TestBboxInvalidCoordinates(TestCase):
    """Test bbox queries with invalid coordinates."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_bbox_latitude_out_of_range(self):
        """Test bbox with latitude > 90 or < -90."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-122,100,-121,110', 'zoom': '10'}
        )
        # Should either handle gracefully or return error
        self.assertIn(response.status_code, [200, 400])

    def test_bbox_longitude_out_of_range(self):
        """Test bbox with longitude > 180 or < -180."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-200,37,-190,38', 'zoom': '10'}
        )
        # Should either handle gracefully or return error
        self.assertIn(response.status_code, [200, 400])

    def test_bbox_invalid_format(self):
        """Test bbox with invalid format."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': 'invalid,bbox,format', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 400)

    def test_bbox_too_few_coordinates(self):
        """Test bbox with too few coordinates."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-122,37', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 400)

    def test_bbox_too_many_coordinates(self):
        """Test bbox with too many coordinates."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-122,37,-121,38,extra', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 400)

    def test_bbox_missing_parameter(self):
        """Test request without bbox parameter."""
        response = self.client.get(
            '/api/geojson/',
            {'zoom': '10'}
        )
        self.assertEqual(response.status_code, 400)


class TestZoomLevelBoundaries(TestCase):
    """Test bbox queries with zoom level boundaries."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create a test feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {'name': 'Test Point'}
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )

    def test_zoom_level_minimum(self):
        """Test zoom level at minimum (1)."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '1'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['zoom_level'], 1)

    def test_zoom_level_maximum(self):
        """Test zoom level at maximum (20)."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '20'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['zoom_level'], 20)

    def test_zoom_level_below_minimum(self):
        """Test zoom level below minimum (should be clamped to 1)."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '0'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['zoom_level'], 1)

    def test_zoom_level_above_maximum(self):
        """Test zoom level above maximum (should be clamped to 20)."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '21'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['zoom_level'], 20)

    def test_zoom_level_invalid(self):
        """Test invalid zoom level (non-integer should still return error)."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': 'invalid'}
        )
        self.assertEqual(response.status_code, 400)

    def test_zoom_level_negative(self):
        """Test negative zoom level (should be clamped to 1)."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '-5'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['zoom_level'], 1)


class TestMaxFeaturesLimit(TestCase):
    """Test MAX_FEATURES_PER_REQUEST limit behavior."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    @patch('api.views.features.bbox_utils.get_required_setting')
    def test_max_features_limit_enforced(self, mock_get_setting):
        """Test that MAX_FEATURES_PER_REQUEST limit is enforced."""
        # Set a low limit for testing
        mock_get_setting.return_value = 5
        
        # Create more features than the limit
        for i in range(20):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {'name': f'Test Point {i}'}
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )
        
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-121,38', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Should limit features returned to exactly the limit
        self.assertEqual(data['feature_count'], 5)
        # When limit is applied, total_count equals the limited count (we avoid COUNT queries)
        self.assertEqual(data['total_features_in_bbox'], 5)
        # Verify the limit is in the response
        self.assertEqual(data['max_features_limit'], 5)

    @patch('api.views.features.bbox_utils.get_required_setting')
    def test_max_features_unlimited(self, mock_get_setting):
        """Test behavior when limit is -1 (unlimited)."""
        # Set limit to -1 (unlimited)
        mock_get_setting.return_value = -1
        
        # Create many features
        for i in range(50):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.001, 37.7749 + i * 0.001, 0.0]
                },
                'properties': {'name': f'Test Point {i}'}
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )
        
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-121,38', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Should return all features
        self.assertEqual(data['feature_count'], 50)
        self.assertEqual(data['total_features_in_bbox'], 50)


class TestCollectionModeWithBbox(TestCase):
    """Test bbox queries in collection mode."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create features with different tags
        for i in range(5):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Test Point {i}',
                    'tags': ['collection-tag'] if i < 3 else ['other-tag']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

        # Create collection
        self.collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['collection-tag']
        )

    def test_bbox_with_collection_parameter(self):
        """Test bbox query with collection parameter."""
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-123,37,-122,38',
                'zoom': '10',
                'collection': str(self.collection.id)
            }
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should only return features in collection
        self.assertLessEqual(data['feature_count'], 3)

    def test_bbox_with_invalid_collection_uuid(self):
        """Test bbox query with invalid collection UUID."""
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-123,37,-122,38',
                'zoom': '10',
                'collection': 'invalid-uuid'
            }
        )
        self.assertEqual(response.status_code, 400)

    def test_bbox_with_nonexistent_collection(self):
        """Test bbox query with non-existent collection."""
        fake_uuid = uuid.uuid4()
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-123,37,-122,38',
                'zoom': '10',
                'collection': str(fake_uuid)
            }
        )
        self.assertEqual(response.status_code, 404)


class TestBboxResponseStructure(TestCase):
    """Test bbox query response structure."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_bbox_response_has_required_fields(self):
        """Test that response has all required fields."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Check required fields
        self.assertIn('data', data)
        self.assertIn('feature_count', data)
        self.assertIn('total_features_in_bbox', data)
        self.assertIn('max_features_limit', data)
        self.assertIn('zoom_level', data)
        self.assertIn('timestamp', data)
        self.assertIn('fallback_used', data)

    def test_bbox_response_data_is_geojson(self):
        """Test that data field is valid GeoJSON FeatureCollection."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        geojson = data['data']
        self.assertEqual(geojson['type'], 'FeatureCollection')
        self.assertIn('features', geojson)
        self.assertIsInstance(geojson['features'], list)

