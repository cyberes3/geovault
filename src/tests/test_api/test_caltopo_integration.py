"""
End-to-end integration tests for CalTopo integration.
All CalTopo API calls are mocked.
"""
from unittest.mock import patch, MagicMock
from django.test import TestCase
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point

from api.models import FeatureStore, ImportQueue
from caltopo_extension.src.backend.models import CalTopoUser
from geo_lib.feature_id import generate_geojson_hash

User = get_user_model()


class TestCalTopoIntegration(TestCase):
    """Test end-to-end CalTopo integration flows."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)
    
    @patch('caltopo.src.backend.services.caltopo_service.CaltopoSession')
    @patch('caltopo.src.backend.views.maps.list_maps')
    @patch('caltopo.src.backend.views.maps.get_map_features')
    @patch('caltopo.src.backend.views.single_import.get_feature')
    @patch('caltopo.src.backend.views.single_import.convert_caltopo_to_geojson')
    def test_complete_flow_connect_list_get_import_feature(
        self, mock_convert, mock_get_feature, mock_get_features, mock_list_maps, mock_session_class
    ):
        """Test complete flow: connect → list maps → get features → import feature."""
        # Step 1: Connect
        mock_session = MagicMock()
        mock_session.getAccountData.return_value = None
        mock_session_class.return_value = mock_session
        
        response = self.client.post('/api/extensions/caltopo-extension/connect/', {
            'account_id': 'abc123',
            'credential_id': '123456789012',
            'credential_key': 'test-key'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        self.assertTrue(CalTopoUser.objects.filter(user=self.user).exists())
        
        # Step 2: List maps
        mock_list_maps.return_value = [
            {'id': 'map1', 'title': 'Test Map 1'},
            {'id': 'map2', 'title': 'Test Map 2'}
        ]
        
        response = self.client.get('/api/extensions/caltopo-extension/maps/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data['maps']), 2)
        
        # Step 3: Get features
        mock_get_features.return_value = [
            {
                'id': 'feature1',
                'properties': {'title': 'Feature 1', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]}
            }
        ]
        
        response = self.client.get('/api/extensions/caltopo-extension/maps/map1/features/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data['features']), 1)
        
        # Step 4: Import feature
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
        
        response = self.client.post('/api/extensions/caltopo-extension/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        
        # Verify feature was created
        feature = FeatureStore.objects.get(user=self.user)
        self.assertEqual(feature.geojson['properties']['caltopo_feature_id'], 'feature1')
        
        # Verify mapping was updated
        caltopo_user = CalTopoUser.objects.get(user=self.user)
        self.assertIn('map1', caltopo_user.imported_features)
        self.assertIn('feature1', caltopo_user.imported_features['map1'])
    
    @patch('caltopo.src.backend.views.single_import.get_feature')
    @patch('caltopo.src.backend.views.single_import.convert_caltopo_to_geojson')
    def test_reimport_flow_import_delete_reimport_verify_new_feature(
        self, mock_convert, mock_get_feature
    ):
        """Test re-import flow: import feature → delete in GeoVault → re-import → verify new feature."""
        # Setup
        
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Step 1: Import feature
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
        geojson_feature_updated = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1 Updated',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1',
                'caltopo_feature_class': 'Marker'
            }
        }
        # Use side_effect to return different values on each call
        mock_convert.side_effect = [geojson_feature, geojson_feature_updated]
        
        response = self.client.post('/api/extensions/caltopo-extension/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        
        # Get the imported feature
        old_feature = FeatureStore.objects.get(user=self.user)
        old_feature_id = old_feature.id
        
        # Step 2: Delete feature in GeoVault
        old_feature.delete()
        
        # Clear rate limit cache before second request
        from django.core.cache import caches
        try:
            cache = caches['rate_limiting']
            cache.clear()
        except Exception:
            pass
        
        # Step 3: Re-import (mock_convert will return the updated feature on second call)
        
        response = self.client.post('/api/extensions/caltopo-extension/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 201)
        
        # Step 4: Verify new feature was created
        new_feature = FeatureStore.objects.get(user=self.user)
        self.assertNotEqual(new_feature.id, old_feature_id)
        self.assertEqual(new_feature.geojson['properties']['name'], 'Feature 1 Updated')
        
        # Verify mapping was updated
        caltopo_user.refresh_from_db()
        self.assertEqual(caltopo_user.imported_features['map1']['feature1'], new_feature.id)
    
    @patch('caltopo.src.backend.views.map_import.get_map_features')
    @patch('caltopo.src.backend.views.map_import.convert_caltopo_to_geojson')
    @patch('geo_lib.processing.jobs.process_job.ProcessJob.enqueue_job')
    @patch('geo_lib.processing.jobs.helpers.status_tracker.status_tracker')
    def test_reimport_map_flow_import_delete_some_reimport_verify_cleanup(
        self, mock_status_tracker, mock_enqueue, mock_convert, mock_get_features
    ):
        """Test re-import flow: import map → delete some features → re-import map → verify cleanup."""
        # Setup
        
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Step 1: Import map (simulate by creating features directly)
        feature1_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        feature1 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature1_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature1_data)
        )
        
        feature2_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0.0]},
            'properties': {
                'name': 'Feature 2',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature2'
            }
        }
        feature2 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_data,
            geometry=Point(-122.4094, 37.7849, 0.0),
            geojson_hash=generate_geojson_hash(feature2_data)
        )
        
        # Set up mapping
        caltopo_user.imported_features = {
            'map1': {
                'feature1': feature1.id,
                'feature2': feature2.id
            }
        }
        caltopo_user.save()
        
        # Step 2: Delete one feature in GeoVault
        feature1.delete()
        
        # Step 3: Re-import map
        mock_get_features.return_value = [
            {
                'id': 'feature2',
                'properties': {'title': 'Feature 2 Updated', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849]}
            },
            {
                'id': 'feature3',
                'properties': {'title': 'Feature 3', 'class': 'Marker'},
                'geometry': {'type': 'Point', 'coordinates': [-122.3994, 37.7949]}
            }
        ]
        
        geojson_feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0.0]},
            'properties': {
                'name': 'Feature 2 Updated',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature2'
            }
        }
        geojson_feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3994, 37.7949, 0.0]},
            'properties': {
                'name': 'Feature 3',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature3'
            }
        }
        
        # Mock convert to return different features on each call
        # convert_caltopo_to_geojson is called twice per feature in the list comprehension
        # So we need to return each feature twice
        mock_convert.side_effect = [
            geojson_feature2, geojson_feature2,  # feature2: first call (check), second call (get value)
            geojson_feature3, geojson_feature3   # feature3: first call (check), second call (get value)
        ]
        
        mock_job = MagicMock()
        mock_job.import_queue_id = 123
        import uuid
        job_id = str(uuid.uuid4())
        mock_status_tracker.create_job.return_value = job_id
        mock_status_tracker.get_job.return_value = mock_job
        
        response = self.client.post('/api/extensions/caltopo-extension/import/map/', {
            'map_id': 'map1'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 200)
        
        # Step 4: Verify cleanup
        # feature2 should be deleted (re-imported)
        self.assertFalse(FeatureStore.objects.filter(id=feature2.id).exists())
        
        # Mapping should be cleared
        caltopo_user.refresh_from_db()
        self.assertEqual(caltopo_user.imported_features.get('map1', {}), {})
    
    @patch('caltopo.src.backend.views.map_import.get_map_features')
    @patch('caltopo.src.backend.views.map_import.convert_caltopo_to_geojson')
    @patch('geo_lib.processing.jobs.process_job.ProcessJob.enqueue_job')
    @patch('geo_lib.processing.jobs.helpers.status_tracker.status_tracker')
    @patch('geo_lib.processing.hooks.execute_import_hooks')
    def test_imported_features_mapping_is_updated_after_map_import(
        self, mock_execute_hooks, mock_status_tracker, mock_enqueue, mock_convert, mock_get_features
    ):
        """Test imported_features mapping is updated correctly after map import."""
        # Setup
        
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Mock map features
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
        
        geojson_feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        geojson_feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[-122.4194, 37.7749, 0.0], [-122.4094, 37.7849, 0.0]]},
            'properties': {
                'name': 'Feature 2',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature2'
            }
        }
        
        # convert_caltopo_to_geojson is called twice per feature in the list comprehension
        # So we need to return each feature twice
        mock_convert.side_effect = [
            geojson_feature1, geojson_feature1,  # feature1: first call (check), second call (get value)
            geojson_feature2, geojson_feature2   # feature2: first call (check), second call (get value)
        ]
        
        mock_job = MagicMock()
        mock_job.import_queue_id = 123
        import uuid
        job_id = str(uuid.uuid4())
        mock_status_tracker.create_job.return_value = job_id
        mock_status_tracker.get_job.return_value = mock_job
        
        # Import map
        response = self.client.post('/api/extensions/caltopo-extension/import/map/', {
            'map_id': 'map1'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 200)
        
        # The mapping will be updated by the hook after processing completes
        # Since we're mocking the processing, we need to manually create the ImportQueue
        # and execute the hook to verify the mapping would be updated
        from api.models import FeatureStore
        from django.contrib.gis.geos import Point
        from caltopo_extension.src.backend.apps import CaltopoExtensionConfig
        
        # Create the import queue item that would normally be created by ProcessJob.enqueue_job
        # Since we're mocking enqueue_job, we need to create it manually
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='caltopo_map_map1.geojson',
            imported=False,
            unparsable=False,
            geofeatures=[]
        )
        
        # Create mock features that would be created by processing
        feature1_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Feature 1',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        feature1 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature1_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature1_data)
        )
        
        feature2_data = {
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[-122.4194, 37.7749, 0.0], [-122.4094, 37.7849, 0.0]]},
            'properties': {
                'name': 'Feature 2',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature2'
            }
        }
        feature2 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_data,
            geometry=Point(-122.4094, 37.7849, 0.0),
            geojson_hash=generate_geojson_hash(feature2_data)
        )
        
        # Execute the actual hook function directly (not through execute_import_hooks since it's mocked)
        config = CaltopoExtensionConfig('caltopo.src.backend', None)
        config.handle_import(import_queue, self.user.id, [feature1, feature2])
        
        # Verify the mapping was updated
        caltopo_user.refresh_from_db()
        self.assertIn('map1', caltopo_user.imported_features)
        self.assertIn('feature1', caltopo_user.imported_features['map1'])
        self.assertIn('feature2', caltopo_user.imported_features['map1'])

