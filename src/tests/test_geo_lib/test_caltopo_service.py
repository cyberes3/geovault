"""
Tests for CalTopo service functions.
All CalTopo API calls are mocked.
"""
from unittest.mock import patch, MagicMock
from django.test import TestCase
from django.contrib.auth import get_user_model

from extensions.caltopo.src.backend.models import CalTopoUser
from extensions.caltopo.src.backend.services.caltopo_api import (
    get_caltopo_session,
    list_maps,
    get_map_features,
    get_feature,
    convert_caltopo_to_geojson
)
from website.extensions.extension_hooks import register_hook, set_extension_context, clear_extension_context

User = get_user_model()


class TestCalTopoService(TestCase):
    """Test CalTopo service functions."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        # Register CalTopo import hook for tests
        # The extension's ready() method might not be called during individual test runs,
        # so we manually register the hook here if it's not already registered
        from website.extensions.extension_hooks import get_hooks
        hooks = get_hooks('import')
        hook_ids = [h[0] for h in hooks]
        if 'caltopo.caltopo_import' not in hook_ids:
            set_extension_context('caltopo')
            from extensions.caltopo.src.backend.apps import CaltopoExtensionConfig
            config = CaltopoExtensionConfig('caltopo', None)
            register_hook('import', 'caltopo_import', config.handle_import)
            clear_extension_context()
    
    def tearDown(self):
        """Clean up after tests."""
        # Clear extension context
        clear_extension_context()
    
    def test_get_caltopo_session_raises_doesnotexist_if_credentials_not_configured(self):
        """Test get_caltopo_session() raises DoesNotExist if credentials not configured."""
        with self.assertRaises(CalTopoUser.DoesNotExist):
            get_caltopo_session(self.user)
    
    @patch('extensions.caltopo.src.backend.services.caltopo_api.CaltopoSession')
    def test_list_maps_handles_missing_rels_in_accountdata(self, mock_session_class):
        """Test list_maps() handles missing 'rels' in accountData gracefully."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        mock_session = MagicMock()
        mock_session.accountData = {}  # No 'rels' key
        mock_session.getAccountData.return_value = None
        mock_session.getMapList.return_value = [{'id': 'map1', 'title': 'Test Map'}]
        mock_session_class.return_value = mock_session
        
        maps = list_maps(self.user)
        
        self.assertEqual(len(maps), 1)
        self.assertEqual(maps[0]['id'], 'map1')
    
    def test_convert_caltopo_to_geojson_converts_feature_correctly(self):
        """Test convert_caltopo_to_geojson() converts feature correctly."""
        caltopo_feature = {
            'id': 'feature1',
            'properties': {
                'title': 'Test Feature',
                'description': 'Test Description',
                'class': 'Marker'
            },
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            }
        }
        
        geojson = convert_caltopo_to_geojson(caltopo_feature, map_id='map1')
        
        self.assertIsNotNone(geojson)
        self.assertEqual(geojson['type'], 'Feature')
        self.assertEqual(geojson['geometry']['type'], 'Point')
        self.assertEqual(geojson['properties']['name'], 'Test Feature')
        self.assertEqual(geojson['properties']['description'], 'Test Description')
    
    def test_convert_caltopo_to_geojson_preserves_caltopo_metadata(self):
        """Test convert_caltopo_to_geojson() preserves CalTopo metadata (map_id, feature_id, class)."""
        caltopo_feature = {
            'id': 'feature1',
            'properties': {
                'title': 'Test Feature',
                'class': 'Marker'
            },
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            }
        }
        
        geojson = convert_caltopo_to_geojson(caltopo_feature, map_id='map1')
        
        props = geojson['properties']
        self.assertEqual(props['caltopo_map_id'], 'map1')
        self.assertEqual(props['caltopo_feature_id'], 'feature1')
        self.assertEqual(props['caltopo_feature_class'], 'Marker')
    
    def test_convert_caltopo_to_geojson_handles_features_without_geometry(self):
        """Test convert_caltopo_to_geojson() handles features without geometry (returns None)."""
        caltopo_feature = {
            'id': 'feature1',
            'properties': {
                'title': 'Test Feature',
                'class': 'Marker'
            }
            # No geometry
        }
        
        geojson = convert_caltopo_to_geojson(caltopo_feature, map_id='map1')
        
        self.assertIsNone(geojson)
    
    def test_convert_caltopo_to_geojson_preserves_styling_properties(self):
        """Test convert_caltopo_to_geojson() preserves styling properties."""
        caltopo_feature = {
            'id': 'feature1',
            'properties': {
                'title': 'Test Feature',
                'class': 'Marker',
                'stroke': '#ff0000',
                'stroke-width': 2,
                'fill': '#00ff00',
                'fill-opacity': 0.5,
                'icon': 'test-icon'
            },
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            }
        }
        
        geojson = convert_caltopo_to_geojson(caltopo_feature, map_id='map1')
        
        props = geojson['properties']
        self.assertEqual(props['stroke'], '#ff0000')
        self.assertEqual(props['stroke-width'], 2)
        self.assertEqual(props['fill'], '#00ff00')
        self.assertEqual(props['fill-opacity'], 0.5)
        self.assertEqual(props['icon'], 'test-icon')
    
    @patch('extensions.caltopo.src.backend.services.caltopo_api.get_caltopo_session')
    def test_hook_updates_imported_features_mapping_after_import(self, mock_get_session):
        """Test hook updates imported_features mapping after import."""
        from geo_lib.processing.hooks import execute_import_hooks
        from api.models import ImportQueue, FeatureStore
        
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Create import queue item
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='caltopo_map_map1.geojson',
            imported=True
        )
        
        # Create features with CalTopo metadata
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Test Feature',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            source=import_item
        )
        
        # Execute hooks
        execute_import_hooks(import_item, self.user.id, [feature])
        
        # Verify mapping was updated
        caltopo_user.refresh_from_db()
        self.assertIn('map1', caltopo_user.imported_features)
        self.assertIn('feature1', caltopo_user.imported_features['map1'])
        self.assertEqual(caltopo_user.imported_features['map1']['feature1'], feature.id)
    
    @patch('extensions.caltopo.src.backend.services.caltopo_api.get_caltopo_session')
    def test_hook_handles_empty_created_features_list(self, mock_get_session):
        """Test hook handles empty created_features list."""
        from geo_lib.processing.hooks import execute_import_hooks
        from api.models import ImportQueue
        
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='caltopo_map_map1.geojson',
            imported=True
        )
        
        # Execute hooks with empty list (should not error)
        execute_import_hooks(import_item, self.user.id, [])
        
        # Should complete without error
    
    @patch('extensions.caltopo.src.backend.services.caltopo_api.get_caltopo_session')
    def test_hook_handles_missing_caltopo_user_gracefully(self, mock_get_session):
        """Test hook handles missing CalTopoUser gracefully (logs warning)."""
        from geo_lib.processing.hooks import execute_import_hooks
        from api.models import ImportQueue, FeatureStore
        
        # Don't create CalTopoUser
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='caltopo_map_map1.geojson',
            imported=True
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {
                'name': 'Test Feature',
                'caltopo_map_id': 'map1',
                'caltopo_feature_id': 'feature1'
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            source=import_item
        )
        
        # Execute hooks (should not error, just log warning)
        execute_import_hooks(import_item, self.user.id, [feature])
        
        # Should complete without error
    
    @patch('extensions.caltopo.src.backend.services.caltopo_api.get_caltopo_session')
    def test_hook_only_updates_mapping_for_features_with_caltopo_metadata(self, mock_get_session):
        """Test hook only updates mapping for features with caltopo_map_id and caltopo_feature_id."""
        from geo_lib.processing.hooks import execute_import_hooks
        from api.models import ImportQueue, FeatureStore
        
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='caltopo_map_map1.geojson',
            imported=True
        )
        
        # Create feature with CalTopo metadata
        feature_with_metadata = FeatureStore.objects.create(
            user=self.user,
            geojson={
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
                'properties': {
                    'name': 'CalTopo Feature',
                    'caltopo_map_id': 'map1',
                    'caltopo_feature_id': 'feature1'
                }
            },
            source=import_item
        )
        
        # Create feature without CalTopo metadata
        feature_without_metadata = FeatureStore.objects.create(
            user=self.user,
            geojson={
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0.0]},
                'properties': {
                    'name': 'Regular Feature'
                    # No caltopo_map_id or caltopo_feature_id
                }
            },
            source=import_item
        )
        
        # Execute hooks
        execute_import_hooks(import_item, self.user.id, [feature_with_metadata, feature_without_metadata])
        
        # Verify only feature with metadata was mapped
        caltopo_user.refresh_from_db()
        self.assertIn('map1', caltopo_user.imported_features)
        self.assertIn('feature1', caltopo_user.imported_features['map1'])
        self.assertEqual(caltopo_user.imported_features['map1']['feature1'], feature_with_metadata.id)
        # Should not have mapping for feature without metadata

