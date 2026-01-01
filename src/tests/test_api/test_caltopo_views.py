"""
Tests for CalTopo API view endpoints.
All CalTopo API calls are mocked.
"""
import json
from unittest.mock import patch, MagicMock
from django.test import TestCase
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point

from api.models import CalTopoUser, FeatureStore, ImportQueue
from api.utils.caltopo_helpers import handle_caltopo_call
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.services.caltopo_service import CalTopoTimeoutError

User = get_user_model()


class TestCalTopoViews(TestCase):
    """Test CalTopo API view endpoints."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)
        
        # Mock CalTopo session
        self.mock_session = MagicMock()
        self.mock_session.getAccountData.return_value = None
        self.mock_session.getMapList.return_value = [
            {'id': 'map1', 'title': 'Test Map 1'},
            {'id': 'map2', 'title': 'Test Map 2'}
        ]
        self.mock_session.openMap.return_value = True
        self.mock_session.getFeatures.return_value = {
            'state': {
                'features': [
                    {
                        'id': 'feature1',
                        'properties': {'title': 'Feature 1', 'class': 'Marker'},
                        'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
                    },
                    {
                        'id': 'feature2',
                        'properties': {'title': 'Feature 2', 'class': 'Shape'},
                        'geometry': {'type': 'LineString', 'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]}
                    }
                ]
            }
        }
        self.mock_session.getFeature.return_value = {
            'id': 'feature1',
            'properties': {'title': 'Feature 1', 'class': 'Marker'},
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
        }
    
    def test_all_endpoints_require_authentication(self):
        """Test that all endpoints require authentication (401 if not logged in)."""
        self.client.logout()
        
        # Test connect endpoint
        response = self.client.post('/api/caltopo/connect/', {})
        self.assertEqual(response.status_code, 401)
        
        # Test status endpoint
        response = self.client.get('/api/caltopo/status/')
        self.assertEqual(response.status_code, 401)
        
        # Test disconnect endpoint
        response = self.client.post('/api/caltopo/disconnect/')
        self.assertEqual(response.status_code, 401)
        
        # Test maps endpoint
        response = self.client.get('/api/caltopo/maps/')
        self.assertEqual(response.status_code, 401)
        
        # Test features endpoint
        response = self.client.get('/api/caltopo/maps/map1/features/')
        self.assertEqual(response.status_code, 401)
        
        # Test single import endpoint
        response = self.client.post('/api/caltopo/import/feature/', {})
        self.assertEqual(response.status_code, 401)
        
        # Test map import endpoint
        response = self.client.post('/api/caltopo/import/map/', {})
        self.assertEqual(response.status_code, 401)
    
    def test_all_endpoints_require_caltopo_connection(self):
        """Test that all endpoints require CalTopo connection (400 if not connected)."""
        # All endpoints except connect/status/disconnect should require connection
        with patch('geo_lib.services.caltopo_service.get_caltopo_session', return_value=None):
            response = self.client.get('/api/caltopo/maps/')
            self.assertEqual(response.status_code, 400)
            data = response.json()
            self.assertIn('CalTopo not connected', data['error'])
    
    @patch('geo_lib.services.caltopo_service.CaltopoSession')
    def test_connect_with_valid_credentials(self, mock_session_class):
        """Test POST /api/caltopo/connect/ with valid credentials (creates CalTopoUser)."""
        mock_session = MagicMock()
        mock_session.getAccountData.return_value = None
        mock_session_class.return_value = mock_session
        
        response = self.client.post('/api/caltopo/connect/', {
            'account_id': 'abc123',
            'credential_id': '123456789012',
            'credential_key': 'test-key-12345'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertTrue(data['connected'])
        
        # Verify CalTopoUser was created
        caltopo_user = CalTopoUser.objects.get(user=self.user)
        caltopo_user.refresh_from_db()  # Ensure we get decrypted value
        self.assertEqual(caltopo_user.account_id, 'abc123')
        self.assertEqual(caltopo_user.credential_id, '123456789012')
        # credential_key should decrypt correctly when accessed
        self.assertEqual(caltopo_user.credential_key, 'test-key-12345')
    
    @patch('geo_lib.services.caltopo_service.CaltopoSession')
    def test_connect_with_invalid_credentials(self, mock_session_class):
        """Test POST /api/caltopo/connect/ with invalid credentials (deletes credentials, returns 400)."""
        mock_session = MagicMock()
        mock_session.getAccountData.side_effect = Exception("Invalid credentials")
        mock_session_class.return_value = mock_session
        
        # First create a CalTopoUser
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='old-key'
        )
        
        response = self.client.post('/api/caltopo/connect/', {
            'account_id': 'abc123',
            'credential_id': '123456789012',
            'credential_key': 'invalid-key'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn('Invalid CalTopo credentials', data['error'])
        
        # Verify CalTopoUser was deleted
        self.assertFalse(CalTopoUser.objects.filter(user=self.user).exists())
    
    @patch('api.views.caltopo.connect_caltopo.get_caltopo_session')
    def test_get_status_returns_connected_true_when_connected(self, mock_get_session):
        """Test GET /api/caltopo/status/ returns connected: true when connected."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Mock the session to return successful account data
        mock_session = MagicMock()
        mock_session.getAccountData.return_value = None
        mock_get_session.return_value = mock_session
        
        response = self.client.get('/api/caltopo/status/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data['connected'])
    
    def test_get_status_returns_connected_false_when_not_connected(self):
        """Test GET /api/caltopo/status/ returns connected: false when not connected."""
        response = self.client.get('/api/caltopo/status/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertFalse(data['connected'])
    
    @patch('api.views.caltopo.connect_caltopo.get_caltopo_session')
    def test_get_status_does_not_expose_account_id(self, mock_get_session):
        """Test GET /api/caltopo/status/ does NOT expose account_id."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Mock the session to return successful account data
        mock_session = MagicMock()
        mock_session.getAccountData.return_value = None
        mock_get_session.return_value = mock_session
        
        response = self.client.get('/api/caltopo/status/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertNotIn('account_id', data)
        self.assertTrue(data['connected'])
    
    def test_disconnect_deletes_caltopo_user_record(self):
        """Test POST /api/caltopo/disconnect/ deletes CalTopoUser record."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        response = self.client.post('/api/caltopo/disconnect/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertFalse(data['connected'])
        
        # Verify CalTopoUser was deleted
        self.assertFalse(CalTopoUser.objects.filter(user=self.user).exists())
    
    @patch('api.views.caltopo.maps.list_maps')
    def test_list_maps_returns_list(self, mock_list_maps):
        """Test GET /api/caltopo/maps/ returns list of maps (mocked)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        mock_list_maps.return_value = [
            {'id': 'map1', 'title': 'Test Map 1'},
            {'id': 'map2', 'title': 'Test Map 2'}
        ]
        
        response = self.client.get('/api/caltopo/maps/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data['maps']), 2)
        self.assertEqual(data['count'], 2)
    
    @patch('api.views.caltopo.maps.get_map_features')
    def test_get_map_features_returns_features_list(self, mock_get_features):
        """Test GET /api/caltopo/maps/<map_id>/features/ returns features list (mocked)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
            }
        ]
        
        response = self.client.get('/api/caltopo/maps/map1/features/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data['features']), 1)
        self.assertEqual(data['features'][0]['id'], 'feature1')
    
    @patch('api.views.caltopo.maps.get_map_features')
    def test_get_map_features_includes_is_imported_flag(self, mock_get_features):
        """Test GET /api/caltopo/maps/<map_id>/features/ includes is_imported flag for each feature."""
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create a feature that was imported
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Imported Feature',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        imported_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Update imported_features mapping
        caltopo_user.imported_features = {'map1': {'feature1': imported_feature.id}}
        caltopo_user.save()
        
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
            },
            {
                'id': 'feature2',
                'properties': {'title': 'Feature 2', 'class': 'Shape'},
                'geometry': {'type': 'LineString', 'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]}
            }
        ]
        
        response = self.client.get('/api/caltopo/maps/map1/features/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        
        # feature1 should be marked as imported
        feature1 = next(f for f in data['features'] if f['id'] == 'feature1')
        self.assertTrue(feature1['is_imported'])
        
        # feature2 should not be marked as imported
        feature2 = next(f for f in data['features'] if f['id'] == 'feature2')
        self.assertFalse(feature2['is_imported'])
    
    @patch('api.views.caltopo.maps.get_map_features')
    def test_get_map_features_includes_is_in_queue_flag(self, mock_get_features):
        """Test GET /api/caltopo/maps/<map_id>/features/ includes is_in_queue flag."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create an import queue item for this map
        ImportQueue.objects.create(
            user=self.user,
            original_filename='caltopo_map_map1.geojson',
            imported=False,
            unparsable=False
        )
        
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
            }
        ]
        
        response = self.client.get('/api/caltopo/maps/map1/features/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data['is_in_queue'])
    
    @patch('api.views.caltopo.maps.get_map_features')
    def test_get_map_features_cleans_up_stale_mappings(self, mock_get_features):
        """Test GET /api/caltopo/maps/<map_id>/features/ cleans up stale mappings (deleted features)."""
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create a mapping for a feature that no longer exists in FeatureStore
        caltopo_user.imported_features = {'map1': {'feature1': 99999}}  # Non-existent ID
        caltopo_user.save()
        
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
            }
        ]
        
        response = self.client.get('/api/caltopo/maps/map1/features/')
        self.assertEqual(response.status_code, 200)
        
        # Refresh from database
        caltopo_user.refresh_from_db()
        # Stale mapping should be cleaned up
        self.assertEqual(caltopo_user.imported_features.get('map1', {}), {})
    
    @patch('api.views.caltopo.maps.get_map_features')
    def test_get_map_features_detects_map_in_import_queue(self, mock_get_features):
        """Test GET /api/caltopo/maps/<map_id>/features/ detects map in import queue."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create import queue item
        ImportQueue.objects.create(
            user=self.user,
            original_filename='caltopo_map_map1.geojson',
            imported=False,
            unparsable=False
        )
        
        mock_get_features.return_value = []
        
        response = self.client.get('/api/caltopo/maps/map1/features/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data['is_in_queue'])
    
    @patch('api.views.caltopo.single_import.get_feature')
    @patch('api.views.caltopo.single_import.convert_caltopo_to_geojson')
    def test_import_feature_successfully(self, mock_convert, mock_get_feature):
        """Test POST /api/caltopo/import/feature/ imports single feature successfully."""
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        caltopo_feature = {
            'id': 'feature1',
            'properties': {'title': 'Feature 1', 'class': 'Marker'},
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
        }
        mock_get_feature.return_value = caltopo_feature
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1',
                'caltopo_feature_class': 'Marker'
            }
        }
        mock_convert.return_value = geojson_feature
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertTrue(data['imported'])
        
        # Verify feature was created
        feature = FeatureStore.objects.get(user=self.user)
        self.assertEqual(feature.geojson['properties']['caltopo_feature_id'], 'feature1')
        
        # Verify mapping was updated
        caltopo_user.refresh_from_db()
        self.assertEqual(caltopo_user.imported_features['map1']['feature1'], feature.id)
    
    def test_import_feature_validates_feature_class(self):
        """Test POST /api/caltopo/import/feature/ validates feature_class (Pydantic validator)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'InvalidClass'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn('Invalid feature_class', data['error'])
    
    def test_import_feature_rejects_invalid_feature_class(self):
        """Test POST /api/caltopo/import/feature/ rejects invalid feature_class."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'NotAValidClass'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn('Invalid feature_class', data['error'])
    
    @patch('api.views.caltopo.single_import.get_feature')
    @patch('api.views.caltopo.single_import.convert_caltopo_to_geojson')
    def test_import_feature_deletes_existing_on_reimport(self, mock_convert, mock_get_feature):
        """Test POST /api/caltopo/import/feature/ deletes existing feature on re-import."""
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create existing feature
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Old Feature', 'caltopo_map_id': 'map1', 'caltopo_feature_id': 'feature1'}
        }
        old_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Set up mapping
        caltopo_user.imported_features = {'map1': {'feature1': old_feature.id}}
        caltopo_user.save()
        
        # Mock new feature
        caltopo_feature = {
            'id': 'feature1',
            'properties': {'title': 'New Feature 1', 'class': 'Marker'},
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
        }
        mock_get_feature.return_value = caltopo_feature
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'New Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        mock_convert.return_value = geojson_feature
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        
        # Old feature should be deleted
        self.assertFalse(FeatureStore.objects.filter(id=old_feature.id).exists())
        
        # New feature should be created
        new_feature = FeatureStore.objects.get(user=self.user)
        self.assertEqual(new_feature.geojson['properties']['name'], 'New Feature 1')
    
    @patch('api.views.caltopo.single_import.get_feature')
    @patch('api.views.caltopo.single_import.convert_caltopo_to_geojson')
    def test_import_feature_updates_imported_features_mapping(self, mock_convert, mock_get_feature):
        """Test POST /api/caltopo/import/feature/ updates imported_features mapping."""
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        caltopo_feature = {
            'id': 'feature1',
            'properties': {'title': 'Feature 1', 'class': 'Marker'},
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
        }
        mock_get_feature.return_value = caltopo_feature
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        mock_convert.return_value = geojson_feature
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        
        # Verify mapping was updated
        caltopo_user.refresh_from_db()
        self.assertIn('map1', caltopo_user.imported_features)
        self.assertIn('feature1', caltopo_user.imported_features['map1'])
        
        feature = FeatureStore.objects.get(user=self.user)
        self.assertEqual(caltopo_user.imported_features['map1']['feature1'], feature.id)
    
    @patch('api.views.caltopo.single_import.get_feature')
    @patch('api.views.caltopo.single_import.convert_caltopo_to_geojson')
    @patch('geo_lib.processing.duplicate_detection.find._find_hash_duplicates')
    def test_import_feature_includes_duplicate_warnings(self, mock_hash_dups, mock_convert, mock_get_feature):
        """Test POST /api/caltopo/import/feature/ includes duplicate warnings in response."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create existing feature with same hash
        existing_feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Existing Feature'}
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=existing_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(existing_feature_data)
        )
        
        caltopo_feature = {
            'id': 'feature1',
            'properties': {'title': 'Feature 1', 'class': 'Marker'},
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
        }
        mock_get_feature.return_value = caltopo_feature
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        mock_convert.return_value = geojson_feature
        
        # Mock duplicate detection to return a duplicate
        mock_hash_dups.return_value = [{
            'existing_features': [{'id': 1, 'geojson': {'properties': {'name': 'Existing Feature'}}}]
        }]
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertIn('warnings', data)
        self.assertTrue(len(data['warnings']) > 0)
    
    @patch('api.views.caltopo.single_import.get_feature')
    @patch('api.views.caltopo.single_import.convert_caltopo_to_geojson')
    def test_import_feature_creates_featurestore_with_3d_geometry(self, mock_convert, mock_get_feature):
        """Test POST /api/caltopo/import/feature/ creates FeatureStore with correct geometry (3D)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        caltopo_feature = {
            'id': 'feature1',
            'properties': {'title': 'Feature 1', 'class': 'Marker'},
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}  # 2D coordinates
        }
        mock_get_feature.return_value = caltopo_feature
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},  # Should be normalized to 3D
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        mock_convert.return_value = geojson_feature
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        
        # Verify feature was created with 3D geometry
        feature = FeatureStore.objects.get(user=self.user)
        self.assertIsNotNone(feature.geometry)
        # Geometry should have Z dimension
        self.assertEqual(feature.geometry.z, 0.0)
    
    @patch('api.views.caltopo.single_import.get_feature')
    @patch('api.views.caltopo.single_import.convert_caltopo_to_geojson')
    def test_import_feature_preserves_caltopo_metadata(self, mock_convert, mock_get_feature):
        """Test POST /api/caltopo/import/feature/ preserves CalTopo metadata in geojson properties."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        caltopo_feature = {
            'id': 'feature1',
            'properties': {'title': 'Feature 1', 'class': 'Marker'},
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
        }
        mock_get_feature.return_value = caltopo_feature
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1',
                'caltopo_feature_class': 'Marker'
            }
        }
        mock_convert.return_value = geojson_feature
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        
        # Verify metadata is preserved
        feature = FeatureStore.objects.get(user=self.user)
        props = feature.geojson['properties']
        self.assertEqual(props['caltopo_map_id'], 'map1')
        self.assertEqual(props['caltopo_feature_id'], 'feature1')
        self.assertEqual(props['caltopo_feature_class'], 'Marker')
    
    @patch('api.views.caltopo.map_import.get_map_features')
    @patch('api.views.caltopo.map_import.convert_caltopo_to_geojson')
    @patch('geo_lib.processing.jobs.process_job.ProcessJob.enqueue_job')
    @patch('geo_lib.processing.jobs.helpers.status_tracker.status_tracker')
    def test_import_map_queues_successfully(self, mock_status_tracker, mock_enqueue, mock_convert, mock_get_features):
        """Test POST /api/caltopo/import/map/ queues map import successfully."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
            }
        ]
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        # convert_caltopo_to_geojson is called twice per feature in the list comprehension
        # So we need to return the feature twice
        mock_convert.side_effect = [geojson_feature, geojson_feature]
        
        # Mock status tracker - use actual job ID format (UUID)
        import uuid
        job_id = str(uuid.uuid4())
        mock_job = MagicMock()
        mock_job.import_queue_id = 123
        mock_status_tracker.create_job.return_value = job_id
        mock_status_tracker.get_job.return_value = mock_job
        
        response = self.client.post('/api/caltopo/import/map/', {
            'map_id': 'map1'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn('job_id', data)
        self.assertIn('import_queue_id', data)
        self.assertEqual(data['feature_count'], 1)
        
        # Verify enqueue was called
        mock_enqueue.assert_called_once()
    
    @patch('api.views.caltopo.map_import.get_map_features')
    def test_import_map_prevents_duplicate_queue_entries(self, mock_get_features):
        """Test POST /api/caltopo/import/map/ prevents duplicate queue entries (409 if already queued)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create existing import queue item
        ImportQueue.objects.create(
            user=self.user,
            original_filename='caltopo_map_map1.geojson',
            imported=False,
            unparsable=False
        )
        
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
            }
        ]
        
        response = self.client.post('/api/caltopo/import/map/', {
            'map_id': 'map1'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 409)
        data = response.json()
        self.assertIn('already in the import queue', data['error'])
    
    @patch('api.views.caltopo.map_import.get_map_features')
    @patch('api.views.caltopo.map_import.convert_caltopo_to_geojson')
    def test_import_map_deletes_existing_features_on_reimport(self, mock_convert, mock_get_features):
        """Test POST /api/caltopo/import/map/ deletes existing features for map on re-import."""
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create existing features
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Old Feature', 'caltopo_map_id': 'map1', 'caltopo_feature_id': 'feature1'}
        }
        old_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Set up mapping
        caltopo_user.imported_features = {'map1': {'feature1': old_feature.id}}
        caltopo_user.save()
        
        mock_get_features.return_value = [
            {
                'id': 'feature2',
                'properties': {'title': 'New Feature', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849]}
            }
        ]
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0.0]},
            'properties': {
                'name': 'New Feature',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature2'
            }
        }
        # convert_caltopo_to_geojson is called twice per feature in the list comprehension
        # So we need to return the feature twice
        mock_convert.side_effect = [geojson_feature, geojson_feature]
        
        with patch('geo_lib.processing.jobs.process_job.ProcessJob.enqueue_job'), \
             patch('geo_lib.processing.jobs.helpers.status_tracker.status_tracker') as mock_tracker:
            import uuid
            job_id = str(uuid.uuid4())
            mock_job = MagicMock()
            mock_tracker.create_job.return_value = job_id
            mock_tracker.get_job.return_value = mock_job
            
            response = self.client.post('/api/caltopo/import/map/', {
                'map_id': 'map1'
            }, content_type='application/json')
        
        self.assertEqual(response.status_code, 200)
        
        # Old feature should be deleted
        self.assertFalse(FeatureStore.objects.filter(id=old_feature.id).exists())
        
        # Mapping should be cleared
        caltopo_user.refresh_from_db()
        self.assertEqual(caltopo_user.imported_features.get('map1', {}), {})
    
    @patch('api.views.caltopo.map_import.get_map_features')
    @patch('api.views.caltopo.map_import.convert_caltopo_to_geojson')
    def test_import_map_clears_imported_features_mapping(self, mock_convert, mock_get_features):
        """Test POST /api/caltopo/import/map/ clears imported_features mapping for map."""
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Set up existing mapping
        caltopo_user.imported_features = {'map1': {'feature1': 123}}
        caltopo_user.save()
        
        mock_get_features.return_value = [
            {
                'id': 'feature2',
                'properties': {'title': 'New Feature', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849]}
            }
        ]
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0.0]},
            'properties': {
                'name': 'New Feature',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature2'
            }
        }
        # convert_caltopo_to_geojson is called twice per feature in the list comprehension
        # So we need to return the feature twice
        mock_convert.side_effect = [geojson_feature, geojson_feature]
        
        with patch('geo_lib.processing.jobs.process_job.ProcessJob.enqueue_job'), \
             patch('geo_lib.processing.jobs.helpers.status_tracker.status_tracker') as mock_tracker:
            import uuid
            job_id = str(uuid.uuid4())
            mock_job = MagicMock()
            mock_tracker.create_job.return_value = job_id
            mock_tracker.get_job.return_value = mock_job
            
            response = self.client.post('/api/caltopo/import/map/', {
                'map_id': 'map1'
            }, content_type='application/json')
        
        self.assertEqual(response.status_code, 200)
        
        # Mapping should be cleared
        caltopo_user.refresh_from_db()
        self.assertEqual(caltopo_user.imported_features.get('map1', {}), {})
    
    @patch('api.views.caltopo.map_import.get_map_features')
    @patch('api.views.caltopo.map_import.convert_caltopo_to_geojson')
    def test_import_map_handles_no_valid_features(self, mock_convert, mock_get_features):
        """Test POST /api/caltopo/import/map/ handles maps with no valid features (returns 400)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': None  # No geometry - should be filtered out
            }
        ]
        
        # convert_caltopo_to_geojson is called twice in the list comprehension (once to check, once to get value)
        # So we need to return None for both calls
        mock_convert.side_effect = [None, None]  # First call returns None, second call (in if check) also returns None
        
        response = self.client.post('/api/caltopo/import/map/', {
            'map_id': 'map1'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn('No valid features', data['error'])
    
    @patch('api.views.caltopo.map_import.get_map_features')
    @patch('api.views.caltopo.map_import.convert_caltopo_to_geojson')
    @patch('api.views.caltopo.map_import.process_job')
    @patch('api.views.caltopo.map_import.status_tracker')
    def test_import_map_returns_job_id_and_import_queue_id(self, mock_status_tracker, mock_process_job, mock_convert, mock_get_features):
        """Test POST /api/caltopo/import/map/ returns job_id and import_queue_id."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
            }
        ]
        
        geojson_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        # convert_caltopo_to_geojson is called twice per feature in the list comprehension
        # So we need to return the feature twice
        mock_convert.side_effect = [geojson_feature, geojson_feature]
        
        # Mock status tracker - use actual job ID format (UUID)
        import uuid
        job_id = str(uuid.uuid4())
        mock_job = MagicMock()
        mock_job.import_queue_id = 456
        mock_status_tracker.create_job.return_value = job_id
        mock_status_tracker.get_job.return_value = mock_job
        
        response = self.client.post('/api/caltopo/import/map/', {
            'map_id': 'map1'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data['job_id'], job_id)
        self.assertEqual(data['import_queue_id'], 456)
    
    @patch('api.views.caltopo.maps.list_maps')
    def test_list_maps_handles_timeout(self, mock_list_maps):
        """Test GET /api/caltopo/maps/ handles CalTopo timeout (returns 504 with CALTOPO_TIMEOUT)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        from geo_lib.services.caltopo_service import CalTopoTimeoutError
        mock_list_maps.side_effect = CalTopoTimeoutError("CalTopo API request timed out")
        
        response = self.client.get('/api/caltopo/maps/')
        self.assertEqual(response.status_code, 504)
        data = response.json()
        self.assertIn('CalTopo request timed out', data['error'])
        self.assertEqual(data['details']['error_code'], 'CALTOPO_TIMEOUT')
    
    @patch('api.views.caltopo.maps.get_map_features')
    def test_get_map_features_handles_timeout(self, mock_get_features):
        """Test GET /api/caltopo/maps/<map_id>/features/ handles CalTopo timeout (returns 504 with CALTOPO_TIMEOUT)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        from geo_lib.services.caltopo_service import CalTopoTimeoutError
        mock_get_features.side_effect = CalTopoTimeoutError("CalTopo API request timed out")
        
        response = self.client.get('/api/caltopo/maps/map1/features/')
        self.assertEqual(response.status_code, 504)
        data = response.json()
        self.assertIn('CalTopo request timed out', data['error'])
        self.assertEqual(data['details']['error_code'], 'CALTOPO_TIMEOUT')
    
    @patch('api.views.caltopo.single_import.get_feature')
    def test_import_feature_handles_timeout(self, mock_get_feature):
        """Test POST /api/caltopo/import/feature/ handles CalTopo timeout (returns 504 with CALTOPO_TIMEOUT)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        from geo_lib.services.caltopo_service import CalTopoTimeoutError
        mock_get_feature.side_effect = CalTopoTimeoutError("CalTopo API request timed out")
        
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 504)
        data = response.json()
        self.assertIn('CalTopo request timed out', data['error'])
        self.assertEqual(data['details']['error_code'], 'CALTOPO_TIMEOUT')
    
    @patch('api.views.caltopo.map_import.get_map_features')
    def test_import_map_handles_timeout(self, mock_get_features):
        """Test POST /api/caltopo/import/map/ handles CalTopo timeout (returns 504 with CALTOPO_TIMEOUT)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        from geo_lib.services.caltopo_service import CalTopoTimeoutError
        mock_get_features.side_effect = CalTopoTimeoutError("CalTopo API request timed out")
        
        response = self.client.post('/api/caltopo/import/map/', {
            'map_id': 'map1'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 504)
        data = response.json()
        self.assertIn('CalTopo request timed out', data['error'])
        self.assertEqual(data['details']['error_code'], 'CALTOPO_TIMEOUT')
    
    @patch('geo_lib.services.caltopo_service.CaltopoSession')
    def test_connect_handles_timeout(self, mock_session_class):
        """Test POST /api/caltopo/connect/ handles CalTopo timeout (returns 504 with CALTOPO_TIMEOUT)."""
        from requests.exceptions import ReadTimeout
        from geo_lib.services.caltopo_service import get_caltopo_session
        
        mock_session = MagicMock()
        mock_session.getAccountData.side_effect = ReadTimeout("Read timed out")
        mock_session_class.return_value = mock_session
        
        response = self.client.post('/api/caltopo/connect/', {
            'account_id': 'abc123',
            'credential_id': '123456789012',
            'credential_key': 'test-key-12345'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 504)
        data = response.json()
        self.assertIn('CalTopo request timed out', data['error'])
        self.assertEqual(data['details']['error_code'], 'CALTOPO_TIMEOUT')
        
        # Verify CalTopoUser was deleted on timeout
        self.assertFalse(CalTopoUser.objects.filter(user=self.user).exists())
    
    def test_handle_caltopo_call_returns_result_on_success(self):
        """Test handle_caltopo_call() returns result and None error on success."""
        def mock_func(arg1, arg2):
            return arg1 + arg2
        
        result, error_resp = handle_caltopo_call(mock_func, 2, 3)
        
        self.assertEqual(result, 5)
        self.assertIsNone(error_resp)
    
    def test_handle_caltopo_call_returns_error_on_timeout(self):
        """Test handle_caltopo_call() returns None result and error response on timeout."""
        def mock_func():
            raise CalTopoTimeoutError("CalTopo API request timed out")
        
        result, error_resp = handle_caltopo_call(mock_func)
        
        self.assertIsNone(result)
        self.assertIsNotNone(error_resp)
        self.assertEqual(error_resp.status_code, 504)
        
        # Parse the JSON response
        import json
        error_data = json.loads(error_resp.content)
        self.assertIn('CalTopo request timed out', error_data['error'])
        self.assertEqual(error_data['details']['error_code'], 'CALTOPO_TIMEOUT')
    
    def test_handle_caltopo_call_re_raises_other_exceptions(self):
        """Test handle_caltopo_call() re-raises exceptions that are not CalTopoTimeoutError."""
        def mock_func():
            raise ValueError("Some other error")
        
        with self.assertRaises(ValueError):
            handle_caltopo_call(mock_func)

