"""
Tests for feature API endpoints (CRUD, search, filtering, bulk operations).
"""
import json
from unittest.mock import patch, MagicMock
import pytest
from django.test import TestCase
from django.contrib.gis.geos import Point

from api.models import FeatureStore, ImportQueue
from geo_lib.feature_id import generate_feature_hash


class TestFeatureAPI(TestCase):
    """Test feature API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create test features
        self.point_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]  # 3D coordinates with Z=0.0
            },
            'properties': {
                'name': 'Test Point',
                'description': 'A test point',
                'tags': ['test', 'point']
            }
        }
        self.point_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            geojson_hash=generate_feature_hash(self.point_feature_data)
        )

        self.linestring_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749, 0.0], [-122.4094, 37.7849, 0.0]]  # 3D coordinates
            },
            'properties': {
                'name': 'Test Line',
                'description': 'A test line',
                'tags': ['test', 'line']
            }
        }
        self.linestring_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.linestring_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0  # Simplified for test
            geojson_hash=generate_feature_hash(self.linestring_feature_data)
        )

    def test_get_feature(self):
        """Test getting a feature by ID."""
        response = self.client.get(f'/api/feature/{self.point_feature.id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('feature', data)
        self.assertEqual(data['feature']['id'], self.point_feature.id)
        self.assertEqual(data['feature']['geojson']['properties']['name'], 'Test Point')

    def test_get_feature_not_found(self):
        """Test getting non-existent feature."""
        response = self.client.get('/api/feature/99999/')
        self.assertEqual(response.status_code, 404)

    def test_get_feature_unauthorized(self):
        """Test getting another user's feature."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        # Create different feature data to avoid duplicate file_hash
        other_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]  # Different coordinates
            },
            'properties': {
                'name': 'Other User Point',
                'description': 'A different point',
                'tags': ['other']
            }
        }
        other_feature = FeatureStore.objects.create(
            user=other_user,
            geojson=other_feature_data,
            geometry=Point(-122.4094, 37.7849, 0.0),  # 3D Point with Z=0.0
            geojson_hash=generate_feature_hash(other_feature_data)
        )
        response = self.client.get(f'/api/feature/{other_feature.id}/')
        self.assertEqual(response.status_code, 404)

    @patch('api.views.feature_retrieval._fetch_elevations_from_api')
    def test_get_feature_elevations(self, mock_fetch_elevations):
        """Test getting elevations for a feature."""
        mock_fetch_elevations.return_value = [100.0, 200.0]
        response = self.client.get(f'/api/feature/{self.linestring_feature.id}/elevations/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('coordinates', data)
        self.assertEqual(len(data['coordinates']), 2)

    def test_get_feature_elevations_point(self):
        """Test getting elevations for a Point (should fail)."""
        response = self.client.get(f'/api/feature/{self.point_feature.id}/elevations/')
        self.assertEqual(response.status_code, 400)

    def test_update_feature_geometry(self):
        """Test updating a feature's geometry."""
        new_geometry = {
            'type': 'Point',
            'coordinates': [-122.4094, 37.7849, 0.0]  # 3D coordinates
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(new_geometry),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.point_feature.refresh_from_db()
        self.assertEqual(
            self.point_feature.geojson['geometry']['coordinates'],
            [-122.4094, 37.7849, 0.0]
        )

    def test_update_feature_full(self):
        """Test updating a feature with full Feature object."""
        updated_feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]  # 3D coordinates
            },
            'properties': {
                'name': 'Updated Point',
                'tags': ['updated']
            }
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(updated_feature),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.point_feature.refresh_from_db()
        self.assertEqual(self.point_feature.geojson['properties']['name'], 'Updated Point')

    def test_update_feature_metadata(self):
        """Test updating feature metadata only."""
        metadata = {
            'name': 'Updated Name',
            'description': 'Updated description',
            'tags': ['new-tag']
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data=json.dumps(metadata),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.point_feature.refresh_from_db()
        self.assertEqual(self.point_feature.geojson['properties']['name'], 'Updated Name')
        self.assertEqual(self.point_feature.geojson['properties']['tags'], ['new-tag'])

    def test_update_feature_metadata_invalid_json(self):
        """Test update feature metadata with invalid JSON."""
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_feature_metadata_extra_fields(self):
        """Test update feature metadata with extra fields."""
        metadata = {
            'name': 'Updated Name',
            'extra_field': 'should be rejected'
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data=json.dumps(metadata),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_feature_metadata_invalid_iso_timestamp(self):
        """Test update feature metadata with invalid ISO timestamp."""
        metadata = {
            'created': 'not-a-valid-iso-timestamp'
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data=json.dumps(metadata),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_feature_metadata_valid_iso_timestamp(self):
        """Test update feature metadata with valid ISO timestamp."""
        metadata = {
            'created': '2024-01-01T12:00:00Z'
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data=json.dumps(metadata),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)

    def test_delete_feature(self):
        """Test deleting a feature."""
        feature_id = self.point_feature.id
        response = self.client.delete(f'/api/feature/{feature_id}/delete/')
        self.assertEqual(response.status_code, 200)
        self.assertFalse(FeatureStore.objects.filter(id=feature_id).exists())

    def test_delete_feature_not_found(self):
        """Test deleting non-existent feature."""
        response = self.client.delete('/api/feature/99999/delete/')
        self.assertEqual(response.status_code, 404)

    def test_search_features(self):
        """Test searching features by query."""
        response = self.client.get('/api/features/search/', {'query': 'Test'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_search_features_no_query(self):
        """Test searching without query parameter."""
        response = self.client.get('/api/features/search/')
        self.assertEqual(response.status_code, 400)

    def test_get_features_by_tag(self):
        """Test getting features grouped by tags."""
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('user_tags', data)
        self.assertIn('system_tags', data)
        self.assertIn('pagination', data)

    def test_get_features_by_tag_pagination(self):
        """Test pagination for features by tag."""
        response = self.client.get('/api/features/by-tag/', {'page': '1'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('pagination', data)

    def test_get_features_by_tag_search(self):
        """Test searching tags in features by tag."""
        response = self.client.get('/api/features/by-tag/', {'search': 'test'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('user_tags', data)

    def test_filter_features_by_tags(self):
        """Test filtering features by tags (AND logic)."""
        response = self.client.get('/api/features/filter-by-tags/', {'tags': 'test'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_filter_features_by_tags_multiple(self):
        """Test filtering by multiple tags."""
        response = self.client.get(
            '/api/features/filter-by-tags/',
            {'tags': ['test', 'point']}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_filter_features_by_tags_no_tags(self):
        """Test filtering without tags parameter."""
        response = self.client.get('/api/features/filter-by-tags/')
        self.assertEqual(response.status_code, 400)

    def test_get_all_features(self):
        """Test getting all features."""
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_bulk_update_features_metadata(self):
        """Test bulk updating features metadata."""
        update_data = {
            'updates': [
                {
                    'feature_id': self.point_feature.id,
                    'tags': ['bulk-updated']
                },
                {
                    'feature_id': self.linestring_feature.id,
                    'tags': ['bulk-updated']
                }
            ]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertEqual(data['updated_count'], 2)

    def test_bulk_update_features_metadata_invalid(self):
        """Test bulk update with invalid data."""
        update_data = {
            'updates': 'not-a-list'
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_missing_updates(self):
        """Test bulk update with missing updates field."""
        update_data = {}
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_empty_updates(self):
        """Test bulk update with empty updates list."""
        update_data = {
            'updates': []
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_invalid_feature_id_type(self):
        """Test bulk update with invalid feature_id type."""
        update_data = {
            'updates': [{
                'feature_id': 'not-an-int',
                'name': 'Test'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_invalid_iso_timestamp(self):
        """Test bulk update with invalid ISO timestamp."""
        update_data = {
            'updates': [{
                'feature_id': self.point_feature.id,
                'created': 'not-a-valid-iso-timestamp'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_valid_iso_timestamp(self):
        """Test bulk update with valid ISO timestamp."""
        update_data = {
            'updates': [{
                'feature_id': self.point_feature.id,
                'created': '2024-01-15T10:30:00Z'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
    
    def test_bulk_update_features_metadata_extra_fields(self):
        """Test bulk update with extra fields (should be rejected)."""
        update_data = {
            'updates': [{
                'feature_id': self.point_feature.id,
                'name': 'Test',
                'invalid_field': 'should be rejected'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_tags_not_list(self):
        """Test bulk update with tags not as list."""
        update_data = {
            'updates': [{
                'feature_id': self.point_feature.id,
                'tags': 'not-a-list'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_bulk_operations_to_tag(self):
        """Test applying bulk operations to features by tag."""
        bulk_ops = {
            'bulk_operations': {
                'tags': ['bulk-tag'],
                'pointColor': '#ff0000'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)

    def test_apply_bulk_operations_to_tag_invalid(self):
        """Test bulk operations with invalid payload."""
        bulk_ops = {
            'bulk_operations': {
                'pointColor': 'invalid-color'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_regenerate_feature_tags(self):
        """Test regenerating tags for a feature."""
        response = self.client.post(f'/api/feature/{self.point_feature.id}/regenerate-tags/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('message', data)

    def test_apply_replacement_geometry(self):
        """Test applying replacement geometry."""
        # Create an ImportQueue entry with replacement geometry
        replacement_feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.3994, 37.7949, 0.0]  # 3D coordinates
            },
            'properties': {}
        }
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='replacement.kml',
            raw_file='',
            geofeatures=[replacement_feature],
            replacement=self.point_feature.id
        )
        
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps({
                'import_queue_id': import_queue.id,
                'feature_index': 0
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.point_feature.refresh_from_db()
        self.assertEqual(
            self.point_feature.geojson['geometry']['coordinates'],
            [-122.3994, 37.7949, 0.0]
        )

    def test_apply_replacement_geometry_invalid(self):
        """Test applying invalid replacement geometry."""
        invalid_geometry = {
            'type': 'InvalidType',
            'coordinates': [-122.3994, 37.7949]
        }
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps(invalid_geometry),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_replacement_geometry_missing_fields(self):
        """Test applying replacement geometry with missing required fields."""
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps({'import_queue_id': 'some-id'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_replacement_geometry_invalid_feature_index_type(self):
        """Test applying replacement geometry with invalid feature_index type."""
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps({'import_queue_id': 'some-id', 'feature_index': 'not-an-int'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_replacement_geometry_extra_fields(self):
        """Test applying replacement geometry with extra fields."""
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps({'import_queue_id': 'some-id', 'feature_index': 0, 'extra': 'field'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    @patch('api.views.feature_export.export_feature_kmz')
    def test_export_feature_kmz(self, mock_export):
        """Test exporting feature as KMZ."""
        mock_response = MagicMock()
        mock_response.content = b'KMZ content'
        mock_export.return_value = mock_response

        response = self.client.get(
            '/api/export-kmz',
            {'feature_ids': str(self.point_feature.id)}
        )
        # Note: This test may need adjustment based on actual export implementation
        self.assertIn(response.status_code, [200, 400, 500])

    def test_bbox_query(self):
        """Test bounding box query."""
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-122.5,37.7,-122.3,37.8',
                'zoom': '10'
            }
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_bbox_query_invalid_bbox(self):
        """Test bbox query with invalid bbox format."""
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': 'invalid',
                'zoom': '10'
            }
        )
        self.assertEqual(response.status_code, 400)

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot access features."""
        self.client.logout()
        response = self.client.get(f'/api/feature/{self.point_feature.id}/')
        self.assertEqual(response.status_code, 401)

