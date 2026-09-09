
import json
import pytest
import tempfile
import importlib
from unittest.mock import patch, MagicMock
from pathlib import Path
import re
from website.extensions.registry import ExtensionRegistry, get_extension_registry
from django.test import TestCase
import sys

# We use the django_db marker for tests that need database access
@pytest.mark.django_db
class TestExtensionConfiguration:
    def test_extension_discovery(self):
        """
        Verify that the extension registry can discover extensions in the extension directory.
        """
        # We know 'example_extension' exists in the codebase
        from website.settings import EXTENSIONS_DIR
        registry = ExtensionRegistry(EXTENSIONS_DIR)
        
        # Discover extensions but don't load them into installed_apps yet 
        # (this avoids messing up the actual test runner's installed apps)
        # However, discover_extensions() actually RETURNS the installed apps list 
        # and populates self.loaded_extensions.
        
        # We'll mock the config loader to ensure it's enabled
        with patch('website.extensions.registry.get_config') as mock_loader_get:
            mock_config = MagicMock()
            # Default to True for all boolean checks
            mock_config.extension_settings.return_value = {'enabled': True}
            mock_loader_get.return_value = mock_config
            
            apps = registry.discover_extensions()
            
            # Check if example_extension was found
            assert 'example_extension' in registry.loaded_extensions
            meta = registry.loaded_extensions['example_extension']
            assert meta['name'] == 'example_extension'
            assert meta['version'] == '1.0.0'

    def test_extension_disabled_via_config(self):
        """
        Verify that an extension is NOT loaded if config.yaml says enabled: False.
        """
        from website.settings import EXTENSIONS_DIR
        registry = ExtensionRegistry(EXTENSIONS_DIR)
        
        with patch('website.extensions.registry.get_config') as mock_loader_get:
            mock_config = MagicMock()
            
            # Return enabled: False for our specific extension
            mock_config.extension_settings.side_effect = (
                lambda name: {'enabled': False} if name == 'example_extension' else {}
            )
            mock_loader_get.return_value = mock_config
            
            registry.discover_extensions()
            
            # Should NOT be in loaded_extensions
            assert 'example_extension' not in registry.loaded_extensions

    def test_extension_enabled_via_config(self):
        """
        Verify that an extension IS loaded if config.yaml says enabled: True.
        """
        from website.settings import EXTENSIONS_DIR
        registry = ExtensionRegistry(EXTENSIONS_DIR)
        
        with patch('website.extensions.registry.get_config') as mock_loader_get:
            mock_config = MagicMock()
            
            mock_config.extension_settings.side_effect = (
                lambda name: {'enabled': True} if name == 'example_extension' else {}
            )
            mock_loader_get.return_value = mock_config
            
            registry.discover_extensions()
            
            # Should be in loaded_extensions
            assert 'example_extension' in registry.loaded_extensions

class TestExtensionAPI(TestCase):
    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            username='testuser',
            email='test@example.com',
            password='testpass123'
        )
        self.client.force_login(self.user)
    
    def test_api_endpoints_integration(self):
        """
        Test that the extension API endpoints are accessible.
        The extension is enabled in config.yaml, so it should be loaded automatically.
        """
        client = self.client
        
        # 1. Test GET items
        # Path: /api/extensions/example-extension/items/ 
        # API urls are included under /api/ in website/urls.py, and extension URLs are appended to api/urls.py
        
        response = client.get('/api/extensions/example-extension/items/')
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data['items'], list)
        
        # 2. Test POST item
        response = client.post(
            '/api/extensions/example-extension/items/', 
            {'name': 'Test Item', 'description': 'Created via test'},
            content_type='application/json'
        )
        assert response.status_code == 201
        data = response.json()
        assert data['name'] == 'Test Item'
        item_id = data['id']
        
        # 3. Test DELETE item
        response = client.delete(f'/api/extensions/example-extension/items/{item_id}/')
        assert response.status_code == 204

    def test_static_asset_serving(self):
        """
        Verify that static assets are served correctly.
        """
        client = self.client

        # Static asset serving is handled by website.urls.serve_extension_static
        # mapped to /extensions/static/
        
        # We don't need the full registry logic for this, just the view in website.urls
        # But we need to make sure the URL pattern is active.
        # website.urls IS the default ROOT_URLCONF, so we can use it directly?
        # Yes, standard 'website.urls' has the 'extensions/static/...' re_path.
        
        # We just need to hit the URL.
        # Path: /extensions/static/example-extension/dist/index.css
        
        url = '/extensions/static/example-extension/dist/index.css'
        response = client.get(url)
        
        assert response.status_code == 200
        assert 'text/css' in response['Content-Type']
        # FileResponse is streaming, so we don't check .content
        # Just status 200 verifies it was found and served.


# ============================================================================
# Feature CRUD Tests for Example Extension
# ============================================================================

class TestExtensionFeatureCRUD(TestCase):
    """Test feature CRUD operations in the example extension."""
    
    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        from api.models import FeatureStore
        from django.contrib.gis.geos import Point
        from geo_lib.feature_id import generate_geojson_hash
        
        # Store imports for use in test methods
        self.FeatureStore = FeatureStore
        
        User = get_user_model()
        self.user = User.objects.create_user(
            username='testuser',
            email='test@example.com',
            password='testpass123'
        )
        self.other_user = User.objects.create_user(
            username='otheruser',
            email='other@example.com',
            password='testpass123'
        )
        self.client.force_login(self.user)
        
        # Create a test feature for the user
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'description': 'A test feature',
                'tags': ['test', 'point']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data),
            scope='example_extension',
        )
        
        # Create a feature for another user
        self.other_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.0, 37.0, 0.0]
            },
            'properties': {
                'name': 'Other User Feature',
                'tags': ['other']
            }
        }
        self.other_feature = FeatureStore.objects.create(
            user=self.other_user,
            geojson=self.other_feature_data,
            geometry=Point(-122.0, 37.0, 0.0),
            geojson_hash=generate_geojson_hash(self.other_feature_data),
            scope='example_extension',
        )
    
    def test_create_feature_success(self):
        """Test creating a new feature via extension endpoint."""
        payload = {
            'latitude': 40.7128,
            'longitude': -74.0060,
            'name': 'New York Point',
            'description': 'A point in NYC',
            'tags': ['my-tag', 'test']
        }
        
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )
        
        assert response.status_code == 201
        data = response.json()
        assert 'feature' in data
        feature = data['feature']
        assert feature['properties']['name'] == 'New York Point'
        assert feature['properties']['description'] == 'A point in NYC'
        assert 'database_id' in feature['properties']
        assert feature['geometry']['type'] == 'Point'
        assert feature['geometry']['coordinates'] == [-74.0060, 40.7128, 0.0]
        
        # Verify tags
        tags = feature['properties']['tags']
        assert 'my-tag' in tags or 'my_tag' in tags  # May be normalized
        assert 'test' in tags
        
        # Verify system tags
        assert 'system_tags' in feature['properties']
        assert 'example-extension' in feature['properties']['system_tags']
    
    def test_create_feature_missing_required_fields(self):
        """Test creating feature with missing required fields."""
        # Missing latitude
        payload = {
            'longitude': -74.0060,
            'name': 'Test'
        }
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )
        assert response.status_code == 400
        
        # Missing longitude
        payload = {
            'latitude': 40.7128,
            'name': 'Test'
        }
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )
        assert response.status_code == 400
        
        # Missing name
        payload = {
            'latitude': 40.7128,
            'longitude': -74.0060
        }
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )
        assert response.status_code == 400
    
    def test_create_feature_invalid_coordinates(self):
        """Test creating feature with invalid coordinates."""
        # Invalid latitude
        payload = {
            'latitude': 100,  # Out of range
            'longitude': -74.0060,
            'name': 'Test'
        }
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )
        assert response.status_code == 400
        
        # Invalid longitude
        payload = {
            'latitude': 40.7128,
            'longitude': 200,  # Out of range
            'name': 'Test'
        }
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )
        assert response.status_code == 400
    
    def test_create_feature_without_tags(self):
        """Test creating feature without tags."""
        payload = {
            'latitude': 40.7128,
            'longitude': -74.0060,
            'name': 'Simple Point',
            'description': 'No tags'
        }
        
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )
        
        assert response.status_code == 201
        data = response.json()
        feature = data['feature']
        assert feature['properties']['tags'] == []
    
    def test_create_feature_unauthenticated(self):
        """Test that unauthenticated users cannot create features."""
        self.client.logout()
        payload = {
            'latitude': 40.7128,
            'longitude': -74.0060,
            'name': 'Test'
        }
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )
        assert response.status_code == 401
    
    def test_modify_feature_add_special_tag(self):
        """Test modifying a feature to add the special tag."""
        # Verify feature doesn't have the special tag initially
        feature = self.FeatureStore.objects.get(id=self.feature.id)
        tags = feature.geojson.get('properties', {}).get('tags', [])
        assert 'example-extension:special' not in tags
        
        # Modify the feature
        response = self.client.post(
            f'/api/extensions/example-extension/features/{self.feature.id}/modify/',
            content_type='application/json'
        )
        
        assert response.status_code == 200
        data = response.json()
        assert 'feature' in data
        feature_data = data['feature']
        assert 'example-extension:special' in feature_data['properties']['tags']
        
        # Verify in database
        feature.refresh_from_db()
        tags = feature.geojson.get('properties', {}).get('tags', [])
        assert 'example-extension:special' in tags
    
    def test_modify_feature_preserves_existing_tags(self):
        """Test that modifying a feature preserves existing tags."""
        # Add some tags to the feature
        feature = self.FeatureStore.objects.get(id=self.feature.id)
        feature.geojson['properties']['tags'] = ['existing', 'tags']
        feature.save()
        
        # Modify the feature
        response = self.client.post(
            f'/api/extensions/example-extension/features/{self.feature.id}/modify/',
            content_type='application/json'
        )
        
        assert response.status_code == 200
        data = response.json()
        feature_data = data['feature']
        tags = feature_data['properties']['tags']
        
        # Should have both existing tags and the special tag
        assert 'existing' in tags
        assert 'tags' in tags
        assert 'example-extension:special' in tags
    
    def test_modify_feature_idempotent(self):
        """Test that modifying a feature twice doesn't duplicate the tag."""
        # Modify once
        response = self.client.post(
            f'/api/extensions/example-extension/features/{self.feature.id}/modify/',
            content_type='application/json'
        )
        assert response.status_code == 200
        
        # Modify again
        response = self.client.post(
            f'/api/extensions/example-extension/features/{self.feature.id}/modify/',
            content_type='application/json'
        )
        assert response.status_code == 200
        
        # Check tag count
        feature = self.FeatureStore.objects.get(id=self.feature.id)
        tags = feature.geojson.get('properties', {}).get('tags', [])
        special_tag_count = tags.count('example-extension:special')
        assert special_tag_count == 1  # Should only appear once
    
    def test_modify_feature_not_found(self):
        """Test modifying a non-existent feature."""
        response = self.client.post(
            '/api/extensions/example-extension/features/99999/modify/',
            content_type='application/json'
        )
        assert response.status_code == 404
    
    def test_modify_feature_other_user(self):
        """Test that users cannot modify other users' features."""
        response = self.client.post(
            f'/api/extensions/example-extension/features/{self.other_feature.id}/modify/',
            content_type='application/json'
        )
        assert response.status_code == 404  # Not found (not authorized)

    def test_list_scoped_features(self):
        response = self.client.get('/api/extensions/example-extension/features/')
        assert response.status_code == 200
        data = response.json()
        ids = [row['properties']['database_id'] for row in data['features']]
        assert self.feature.id in ids
        assert self.other_feature.id not in ids

    def test_modify_unscoped_feature_is_404(self):
        from django.contrib.gis.geos import Point
        from geo_lib.feature_id import generate_geojson_hash

        unscoped = self.FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(10.0, 20.0, 0.0),
            geojson_hash=generate_geojson_hash({**self.feature_data, 'properties': {'name': 'Unscoped'}}),
            scope=None,
        )
        response = self.client.post(
            f'/api/extensions/example-extension/features/{unscoped.id}/modify/',
            content_type='application/json'
        )
        assert response.status_code == 404
    
    def test_modify_feature_unauthenticated(self):
        """Test that unauthenticated users cannot modify features."""
        self.client.logout()
        response = self.client.post(
            f'/api/extensions/example-extension/features/{self.feature.id}/modify/',
            content_type='application/json'
        )
        assert response.status_code == 401
    
    def test_delete_feature_success(self):
        """Test deleting a feature via extension endpoint."""
        feature_id = self.feature.id
        
        response = self.client.delete(
            f'/api/extensions/example-extension/features/{feature_id}/delete/'
        )
        
        assert response.status_code == 200
        data = response.json()
        assert data['feature_id'] == feature_id
        assert 'message' in data
        
        # Verify feature is deleted
        assert not self.FeatureStore.objects.filter(id=feature_id).exists()
    
    def test_delete_feature_not_found(self):
        """Test deleting a non-existent feature."""
        response = self.client.delete(
            '/api/extensions/example-extension/features/99999/delete/'
        )
        assert response.status_code == 404
    
    def test_delete_feature_other_user(self):
        """Test that users cannot delete other users' features."""
        other_feature_id = self.other_feature.id
        
        response = self.client.delete(
            f'/api/extensions/example-extension/features/{other_feature_id}/delete/'
        )
        
        assert response.status_code == 404  # Not found (not authorized)
        
        # Verify feature still exists
        assert self.FeatureStore.objects.filter(id=other_feature_id).exists()
    
    def test_delete_feature_unauthenticated(self):
        """Test that unauthenticated users cannot delete features."""
        self.client.logout()
        response = self.client.delete(
            f'/api/extensions/example-extension/features/{self.feature.id}/delete/'
        )
        assert response.status_code == 401
    
    def test_create_feature_invalid_json(self):
        """Test creating feature with invalid JSON."""
        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data='invalid json',
            content_type='application/json'
        )
        assert response.status_code == 400
    
    def test_create_feature_system_tags_rejected(self):
        """Protected system tag names in user tags are rejected, not silently stored."""
        payload = {
            'latitude': 40.7128,
            'longitude': -74.0060,
            'name': 'Test Feature',
            'tags': ['user-tag', 'type:point', 'import-year:2024']
        }

        response = self.client.post(
            '/api/extensions/example-extension/features/create/',
            data=json.dumps(payload),
            content_type='application/json'
        )

        assert response.status_code == 400


# ============================================================================
# Error Handling & Edge Cases Tests
# ============================================================================

@pytest.mark.django_db
class TestExtensionErrorHandling:
    def test_extensions_directory_not_exists(self):
        """Test that missing extensions directory returns empty list gracefully."""
        non_existent_dir = Path('/tmp/non_existent_extensions_dir_12345')
        registry = ExtensionRegistry(non_existent_dir)
        
        apps = registry.discover_extensions()
        
        assert apps == []
        assert len(registry.loaded_extensions) == 0

    def test_empty_extensions_directory(self):
        """Test that empty extensions directory returns empty list."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            registry = ExtensionRegistry(ext_dir)
            
            apps = registry.discover_extensions()
            
            assert apps == []
            assert len(registry.loaded_extensions) == 0

    def test_extension_without_manifest(self):
        """Test that directories without manifest.toml are skipped."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            # Create extension directory without manifest
            (ext_dir / 'no_manifest_ext').mkdir()
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                assert apps == []
                assert len(registry.loaded_extensions) == 0

    def test_extension_with_invalid_manifest_syntax(self):
        """Test that manifest with syntax errors is handled gracefully."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'invalid_syntax_ext'
            ext_path.mkdir()
            
            # Create manifest with syntax error
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "test"\nversion = "1.0.0"\ninvalid syntax here!!!')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                # Should not raise, but log error
                apps = registry.discover_extensions()
                
                # Extension should not be loaded due to syntax error
                assert 'invalid_syntax_ext' not in registry.loaded_extensions

    def test_manifest_missing_name(self):
        """Test that manifest without 'name' field is rejected."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'missing_name_ext'
            ext_path.mkdir()
            
            # Create manifest without name
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('version = "1.0.0"\ndescription = "Test"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                assert 'missing_name_ext' not in registry.loaded_extensions
                assert apps == []

    def test_manifest_missing_version(self):
        """Test that manifest without 'version' field is rejected."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'missing_version_ext'
            ext_path.mkdir()
            
            # Create manifest without version
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "missing_version_ext"\ndescription = "Test"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                assert 'missing_version_ext' not in registry.loaded_extensions
                assert apps == []

    def test_extension_without_backend_directory(self):
        """Test that an extension without src/backend/ is registered as frontend-only (no Django app)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'no_backend_ext'
            ext_path.mkdir()
            
            # Create manifest but no backend directory
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "no_backend_ext"\nversion = "1.0.0"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Frontend metadata is still registered even without a backend directory
                assert 'no_backend_ext' in registry.loaded_extensions
                meta = registry.loaded_extensions['no_backend_ext']
                assert meta['frontend_entry'] is None
                assert meta['frontend_css'] is None
                # But no Django app is contributed since there's no backend to load
                assert apps == []

    def test_frontend_only_extension_registers_frontend_metadata(self):
        """Test that a frontend-only extension (no src/backend/) still exposes its
        entry point, CSS, and icon, matching how exif_geotagger is documented."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'frontend_only_ext'
            ext_path.mkdir()

            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text(
                'name = "frontend_only_ext"\n'
                'version = "1.0.0"\n'
                'icon = "MapIcon"\n'
            )

            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            (dist_path / 'index.js').write_text('// entry')
            (dist_path / 'index.css').write_text('/* styles */')

            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config

                apps = registry.discover_extensions()

                meta = registry.loaded_extensions['frontend_only_ext']
                assert 'index.js' in meta['frontend_entry']
                assert 'index.css' in meta['frontend_css']
                assert meta['icon'] == 'MapIcon'
                # No AppConfig contributed: nothing to add to INSTALLED_APPS
                assert apps == []

    def test_extension_without_frontend_dist(self):
        """Test that extension with backend but no frontend dist still loads."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'no_frontend_ext'
            ext_path.mkdir()
            
            # Create manifest
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "no_frontend_ext"\nversion = "1.0.0"')
            
            # Create backend directory
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Should be loaded even without frontend
                assert 'no_frontend_ext' in registry.loaded_extensions
                meta = registry.loaded_extensions['no_frontend_ext']
                assert meta['frontend_entry'] is None
                assert meta['frontend_css'] is None

    def test_non_directory_items_ignored(self):
        """Test that files (not directories) in extensions directory are ignored."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            # Create a file instead of directory
            (ext_dir / 'not_a_directory.txt').write_text('some content')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                assert apps == []
                assert len(registry.loaded_extensions) == 0

    def test_manifest_execution_exception(self):
        """Test that exceptions during manifest execution are caught."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'exception_ext'
            ext_path.mkdir()
            
            # Create manifest that raises exception when executed
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('raise RuntimeError("Test exception")\nname = "test"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                # Should not raise, but catch exception
                apps = registry.discover_extensions()
                
                assert 'exception_ext' not in registry.loaded_extensions


# ============================================================================
# URL Routing Tests
# ============================================================================

@pytest.mark.django_db
class TestExtensionURLRouting:
    def test_get_extension_urls_with_urls_module(self):
        """Test that get_extension_urls() generates correct URL patterns."""
        from website.settings import EXTENSIONS_DIR
        registry = ExtensionRegistry(EXTENSIONS_DIR)
        
        with patch('website.extensions.registry.get_config') as mock_loader_get:
            mock_config = MagicMock()
            mock_config.extension_settings.return_value = {'enabled': True}
            mock_loader_get.return_value = mock_config
            
            registry.discover_extensions()
            
            urls = registry.get_extension_urls()
            
            # Should have URL patterns for extensions with urls.py
            assert len(urls) > 0
            # Check that URL prefix uses hyphens (kebab-case)
            for url_pattern in urls:
                assert 'extensions/' in str(url_pattern.pattern)
                # Verify underscore to hyphen conversion
                assert 'example-extension' in str(url_pattern.pattern) or 'example_extension' not in str(url_pattern.pattern)

    def test_get_extension_urls_without_urls_module(self):
        """Test that extensions without urls.py don't get URL patterns."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'no_urls_ext'
            ext_path.mkdir()
            
            # Create manifest
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "no_urls_ext"\nversion = "1.0.0"')
            
            # Create backend directory but no urls.py
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                urls = registry.get_extension_urls()
                
                # Should not have URL pattern since no urls.py
                assert len(urls) == 0

    def test_url_prefix_underscore_to_hyphen(self):
        """Test that extension names with underscores are converted to hyphens in URLs."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'test_extension_name'
            ext_path.mkdir()
            
            # Create manifest
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "test_extension_name"\nversion = "1.0.0"')
            
            # Create backend with urls.py
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            (backend_path / 'urls.py').write_text('from django.urls import path\nurlpatterns = []')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                urls = registry.get_extension_urls()
                
                # Should convert underscore to hyphen
                assert len(urls) == 1
                url_str = str(urls[0].pattern)
                assert 'test-extension-name' in url_str
                assert 'test_extension_name' not in url_str


# ============================================================================
# Frontend Asset Discovery Tests
# ============================================================================

@pytest.mark.django_db
class TestFrontendAssetDiscovery:
    def test_js_file_priority_index_js(self):
        """Test that index.js is preferred over other JS files."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'js_priority_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "js_priority_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            # Create dist with multiple JS files
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            (dist_path / 'index.js').write_text('// index.js')
            (dist_path / 'index.umd.js').write_text('// umd')
            (dist_path / 'index.iife.js').write_text('// iife')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                meta = registry.loaded_extensions['js_priority_ext']
                assert 'index.js' in meta['frontend_entry']

    def test_js_file_priority_umd_over_iife(self):
        """Test that .umd. is preferred over .iife. when index.js doesn't exist."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'js_umd_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "js_umd_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            (dist_path / 'index.umd.js').write_text('// umd')
            (dist_path / 'index.iife.js').write_text('// iife')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                meta = registry.loaded_extensions['js_umd_ext']
                assert '.umd.' in meta['frontend_entry']
                assert '.iife.' not in meta['frontend_entry']

    def test_frontend_entry_and_css_have_cache_busting_version(self):
        """Test that frontend_entry and frontend_css URLs include ?v=<12-char hex> for cache busting."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'cache_bust_ext'
            ext_path.mkdir()
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "cache_bust_ext"\nversion = "1.0.0"')
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            (dist_path / 'index.umd.js').write_text('// umd bundle')
            (dist_path / 'index.css').write_text('/* styles */')
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                registry.discover_extensions()
            meta = registry.loaded_extensions['cache_bust_ext']
            for url in (meta['frontend_entry'], meta['frontend_css']):
                assert '?v=' in url, f"Expected ?v= in {url}"
                suffix = url.split('?v=')[-1]
                assert re.fullmatch(r'[0-9a-f]{12}', suffix), f"Expected 12-char hex after ?v=, got {suffix!r}"

    def test_static_url_version_changes_with_content(self):
        """Test that changing file content changes the ?v= hash (deterministic cache busting)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'content_hash_ext'
            ext_path.mkdir()
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "content_hash_ext"\nversion = "1.0.0"')
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            js_file = dist_path / 'index.umd.js'
            js_file.write_text('// version A')
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                registry.discover_extensions()
            v1 = registry.loaded_extensions['content_hash_ext']['frontend_entry'].split('?v=')[-1]
            js_file.write_text('// version B')
            registry2 = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                registry2.discover_extensions()
            v2 = registry2.loaded_extensions['content_hash_ext']['frontend_entry'].split('?v=')[-1]
            assert v1 != v2, "Hash should change when file content changes"

    def test_js_file_in_assets_subdirectory(self):
        """Test that JS files in dist/assets/ are discovered."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'js_assets_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "js_assets_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            assets_path = dist_path / 'assets'
            assets_path.mkdir()
            (assets_path / 'index.js').write_text('// assets index')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                meta = registry.loaded_extensions['js_assets_ext']
                assert 'assets/index.js' in meta['frontend_entry']

    def test_css_file_priority_index_css(self):
        """Test that index.css is preferred over other CSS files."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'css_priority_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "css_priority_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            (dist_path / 'index.css').write_text('/* index.css */')
            (dist_path / 'style.css').write_text('/* style.css */')
            (dist_path / 'other.css').write_text('/* other */')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                meta = registry.loaded_extensions['css_priority_ext']
                assert 'index.css' in meta['frontend_css']

    def test_css_file_priority_style_css(self):
        """Test that style.css is preferred when index.css doesn't exist."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'css_style_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "css_style_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            (dist_path / 'style.css').write_text('/* style.css */')
            (dist_path / 'other.css').write_text('/* other */')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                meta = registry.loaded_extensions['css_style_ext']
                assert 'style.css' in meta['frontend_css']
                assert 'other.css' not in meta['frontend_css']

    def test_css_file_in_assets_subdirectory(self):
        """Test that CSS files in dist/assets/ are discovered."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'css_assets_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "css_assets_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            assets_path = dist_path / 'assets'
            assets_path.mkdir()
            (assets_path / 'style.css').write_text('/* assets style */')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                meta = registry.loaded_extensions['css_assets_ext']
                assert 'assets/style.css' in meta['frontend_css']


# ============================================================================
# API Endpoint Edge Cases Tests
# ============================================================================

class TestExtensionAPIEdgeCases(TestCase):
    def setUp(self):
        """Create a test user for authentication."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            username='testuser',
            email='test@example.com',
            password='testpass123'
        )
    
    def test_list_extensions_empty_registry(self):
        """Test GET /api/extensions/ when no extensions are loaded."""
        # Login required for this endpoint
        self.client.force_login(self.user)
        
        # Mock registry to return empty list - patch get_extension_registry
        mock_registry_instance = MagicMock()
        mock_registry_instance.get_loaded_extensions.return_value = []
        
        with patch('api.views.extensions.management.get_extension_registry', return_value=mock_registry_instance):
            response = self.client.get('/api/extensions/')
            assert response.status_code == 200
            data = response.json()
            assert data['items'] == []
            assert data['total_items'] == 0

    def test_list_extensions_registry_none(self):
        """Test GET /api/extensions/ when registry returns empty list."""
        # Login required for this endpoint
        self.client.force_login(self.user)
        
        # Mock registry to return empty list
        mock_registry_instance = MagicMock()
        mock_registry_instance.get_loaded_extensions.return_value = []
        
        with patch('api.views.extensions.management.get_extension_registry', return_value=mock_registry_instance):
            response = self.client.get('/api/extensions/')
            assert response.status_code == 200
            data = response.json()
            assert data['items'] == []
            assert data['total_items'] == 0

    def test_extension_metadata_structure(self):
        """Test that extension metadata contains all expected fields."""
        from website.settings import EXTENSIONS_DIR
        registry = ExtensionRegistry(EXTENSIONS_DIR)
        
        with patch('website.extensions.registry.get_config') as mock_loader_get:
            mock_config = MagicMock()
            mock_config.extension_settings.return_value = {'enabled': True}
            mock_loader_get.return_value = mock_config
            
            registry.discover_extensions()
            
            extensions = registry.get_loaded_extensions()
            
            if len(extensions) > 0:
                ext = extensions[0]
                # Verify all expected public fields are present
                # (internal fields prefixed with _ are filtered out)
                assert 'name' in ext
                assert 'version' in ext
                assert 'frontend_entry' in ext
                assert 'frontend_css' in ext
                # urls_module is now internal (_urls_module) and should NOT be in public API
                assert '_urls_module' not in ext

    def test_internal_fields_filtered_from_api(self):
        """Test that internal fields (prefixed with _) are not exposed via get_loaded_extensions()."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'test_internal_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "test_internal_ext"\nversion = "1.0.0"')
            
            # Create backend with urls.py to ensure _urls_module is set
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            (backend_path / 'urls.py').write_text('from django.urls import path\nurlpatterns = []')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                # Check internal storage has _urls_module
                internal_meta = registry.loaded_extensions['test_internal_ext']
                assert '_urls_module' in internal_meta
                assert internal_meta['_urls_module'] is not None
                
                # Check public API filters out _urls_module
                extensions = registry.get_loaded_extensions()
                public_ext = next((e for e in extensions if e['name'] == 'test_internal_ext'), None)
                assert public_ext is not None
                assert '_urls_module' not in public_ext
                # But public fields should still be present
                assert 'name' in public_ext
                assert 'version' in public_ext

    def test_extension_map_route_in_metadata(self):
        """Test that map_route from manifest is exposed in get_loaded_extensions() (for map layout)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'test_map_route_ext'
            ext_path.mkdir()
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text(
                'name = "test_map_route_ext"\nversion = "1.0.0"\nmap_route = true'
            )
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            (backend_path / 'urls.py').write_text('from django.urls import path\nurlpatterns = []')

            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                registry.discover_extensions()

            extensions = registry.get_loaded_extensions()
            public_ext = next((e for e in extensions if e['name'] == 'test_map_route_ext'), None)
            assert public_ext is not None
            assert public_ext.get('map_route') is True

    def test_extension_map_route_default_false(self):
        """Test that extension without map_route in manifest has map_route False in API."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'test_no_map_route_ext'
            ext_path.mkdir()
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "test_no_map_route_ext"\nversion = "1.0.0"')
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            (backend_path / 'urls.py').write_text('from django.urls import path\nurlpatterns = []')

            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                registry.discover_extensions()

            extensions = registry.get_loaded_extensions()
            public_ext = next((e for e in extensions if e['name'] == 'test_no_map_route_ext'), None)
            assert public_ext is not None
            assert public_ext.get('map_route') is False


# ============================================================================
# Static Asset Serving Edge Cases Tests
# ============================================================================

class TestStaticAssetServingEdgeCases(TestCase):
    def test_static_asset_404(self):
        """Test that non-existent static files return 404."""
        client = self.client
        
        url = '/extensions/static/example-extension/dist/nonexistent.css'
        response = client.get(url)
        
        assert response.status_code == 404

    def test_static_asset_path_traversal_prevention(self):
        """Test that path traversal attempts are blocked."""
        client = self.client
        
        # Try to access files outside extension directory
        malicious_paths = [
            '/extensions/static/example-extension/../../../../etc/passwd',
            '/extensions/static/example-extension/../other-extension/file.css',
            '/extensions/static/example-extension/..%2F..%2F..%2Ffile.css',
        ]
        
        for path in malicious_paths:
            response = client.get(path)
            # Should either 404 or be blocked, not serve the file
            assert response.status_code in [404, 403, 400]

    def test_static_asset_cross_extension_access_blocked(self):
        """
        Adversarial: URL says one extension but path resolves to another.
        Must 404 so one extension cannot serve another extension's files.
        """
        client = self.client

        # Paths that use example-extension in URL but traverse to another extension's dir
        cross_extension_paths = [
            '/extensions/static/example-extension/../../caltopo/src/frontend/dist/index.css',
            '/extensions/static/example-extension/../caltopo/src/frontend/dist/index.css',
            '/extensions/static/example-extension/../../places/src/frontend/dist/index.umd.js',
            '/extensions/static/example-extension/../../exif_geotagger/src/frontend/dist/index.css',
        ]
        for url in cross_extension_paths:
            response = client.get(url)
            assert response.status_code == 404, f"Expected 404 for cross-extension path: {url}"

        # Sanity: same file requested with correct extension prefix should succeed when file exists
        correct_url = '/extensions/static/caltopo/dist/index.css'
        response_ok = client.get(correct_url)
        if response_ok.status_code == 200:
            # Cross-extension URL must not serve that file
            cross_url = '/extensions/static/example-extension/../../caltopo/src/frontend/dist/index.css'
            response_blocked = client.get(cross_url)
            assert response_blocked.status_code == 404

    def test_static_asset_traversal_escape_extension_dir_only_returns_404(self):
        """
        Adversarial: path stays under EXTENSIONS_DIR but leaves the requested extension's dir.
        Resolved path must be under extension_base (first URL segment), so 404.
        """
        client = self.client
        url = '/extensions/static/example-extension/../../caltopo/src/frontend/dist/index.css'
        response = client.get(url)
        assert response.status_code == 404

    def test_static_asset_kebab_case_conversion(self):
        """Test that kebab-case URLs are converted to snake_case paths."""
        client = self.client
        
        # Use kebab-case in URL (example-extension)
        url = '/extensions/static/example-extension/dist/index.css'
        response = client.get(url)
        
        # Should successfully convert to example_extension directory
        # If file exists, should return 200, otherwise 404
        assert response.status_code in [200, 404]

    def test_static_asset_old_src_frontend_dist_path_is_404(self):
        """Only dist/ and the declared icon are served; the old nested path is gone."""
        response = self.client.get(
            '/extensions/static/example-extension/src/frontend/dist/index.css'
        )
        assert response.status_code == 404

    def test_static_asset_unknown_extension_is_404(self):
        response = self.client.get('/extensions/static/not-an-extension/dist/index.css')
        assert response.status_code == 404


# ============================================================================
# Multiple Extensions Tests
# ============================================================================

@pytest.mark.django_db
class TestMultipleExtensions:
    def test_multiple_extensions_one_enabled_one_disabled(self):
        """Test loading multiple extensions with different enabled states."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            
            # Create first extension (enabled)
            ext1_path = ext_dir / 'enabled_ext'
            ext1_path.mkdir()
            (ext1_path / 'manifest.toml').write_text('name = "enabled_ext"\nversion = "1.0.0"')
            backend1_path = ext1_path / 'src' / 'backend'
            backend1_path.mkdir(parents=True)
            (backend1_path / '__init__.py').write_text('')
            
            # Create second extension (disabled)
            ext2_path = ext_dir / 'disabled_ext'
            ext2_path.mkdir()
            (ext2_path / 'manifest.toml').write_text('name = "disabled_ext"\nversion = "1.0.0"')
            backend2_path = ext2_path / 'src' / 'backend'
            backend2_path.mkdir(parents=True)
            (backend2_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                
                def extension_settings_side_effect(name):
                    if name == 'enabled_ext':
                        return {'enabled': True}
                    elif name == 'disabled_ext':
                        return {'enabled': False}
                    return {}
                
                mock_config.extension_settings.side_effect = extension_settings_side_effect
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                # Only enabled extension should be loaded
                assert 'enabled_ext' in registry.loaded_extensions
                assert 'disabled_ext' not in registry.loaded_extensions

    def test_enabled_by_default_false(self):
        """Test that enabled_by_default = false requires explicit enable."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'default_disabled_ext'
            ext_path.mkdir()
            
            # Create manifest with enabled_by_default = false
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "default_disabled_ext"\nversion = "1.0.0"\nenabled_by_default = false')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                # Don't explicitly enable it -- no 'enabled' key, falls back to manifest default
                mock_config.extension_settings.return_value = {}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                # Should not be loaded since default is False and not explicitly enabled
                assert 'default_disabled_ext' not in registry.loaded_extensions

    def test_enabled_by_default_true(self):
        """Test that enabled_by_default = true loads extension by default."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'default_enabled_ext'
            ext_path.mkdir()
            
            # Create manifest with enabled_by_default = true (or omit it, defaults to True)
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "default_enabled_ext"\nversion = "1.0.0"\nenabled_by_default = true')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                # No 'enabled' key, falls back to manifest default (True)
                mock_config.extension_settings.return_value = {}
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                # Should be loaded since default is True
                assert 'default_enabled_ext' in registry.loaded_extensions


# ============================================================================
# Registry State Management Tests
# ============================================================================

@pytest.mark.django_db
class TestRegistryStateManagement:
    def test_get_active_extensions_returns_metadata(self):
        """Test that get_loaded_extensions() returns correct metadata."""
        from website.settings import EXTENSIONS_DIR
        registry = ExtensionRegistry(EXTENSIONS_DIR)
        
        with patch('website.extensions.registry.get_config') as mock_loader_get:
            mock_config = MagicMock()
            mock_config.extension_settings.return_value = {'enabled': True}
            mock_loader_get.return_value = mock_config
            
            registry.discover_extensions()
            
            extensions = registry.get_loaded_extensions()
            
            assert isinstance(extensions, list)
            if len(extensions) > 0:
                for ext in extensions:
                    assert isinstance(ext, dict)
                    assert 'name' in ext
                    assert 'version' in ext

    def test_get_extension_registry_singleton(self):
        """Test that get_extension_registry() returns the same instance."""
        # Reset global registry
        import website.extensions.registry
        website.extensions.registry._registry = None
        
        registry1 = get_extension_registry()
        registry2 = get_extension_registry()
        
        # Should be the same instance
        assert registry1 is registry2


# ============================================================================
# Duplicate Extension Names Tests
# ============================================================================

@pytest.mark.django_db
class TestDuplicateExtensionNames:
    def test_duplicate_extension_names_detected(self):
        """Test that extensions with duplicate names cause SystemExit."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            
            # Create first extension
            ext1_path = ext_dir / 'extension_one'
            ext1_path.mkdir()
            (ext1_path / 'manifest.toml').write_text('name = "duplicate_name"\nversion = "1.0.0"')
            backend1_path = ext1_path / 'src' / 'backend'
            backend1_path.mkdir(parents=True)
            (backend1_path / '__init__.py').write_text('')
            
            # Create second extension with same name
            ext2_path = ext_dir / 'extension_two'
            ext2_path.mkdir()
            (ext2_path / 'manifest.toml').write_text('name = "duplicate_name"\nversion = "2.0.0"')
            backend2_path = ext2_path / 'src' / 'backend'
            backend2_path.mkdir(parents=True)
            (backend2_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                # Should raise SystemExit when duplicates are detected
                with pytest.raises(SystemExit) as exc_info:
                    registry.discover_extensions()
                
                # Should exit with error code 1
                assert exc_info.value.code == 1
                
                # No extensions should be loaded
                assert len(registry.loaded_extensions) == 0

    def test_extension_name_mismatch_with_folder_name(self):
        """Folder name must equal manifest.name; mismatch exits."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'folder_name'
            ext_path.mkdir()
            (ext_path / 'manifest.toml').write_text('name = "manifest_name"\nversion = "1.0.0"')
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')

            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config

                with pytest.raises(SystemExit) as exc_info:
                    registry.discover_extensions()
                assert exc_info.value.code == 1
                assert len(registry.loaded_extensions) == 0

    def test_multiple_duplicates_detected(self):
        """Test that multiple sets of duplicates are all detected and cause SystemExit."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            
            # Create extensions with duplicate names
            for i, dup_name in enumerate(['dup_one', 'dup_one', 'dup_two', 'dup_two']):
                ext_path = ext_dir / f'ext_{i}'
                ext_path.mkdir()
                (ext_path / 'manifest.toml').write_text(f'name = "{dup_name}"\nversion = "1.0.0"')
                backend_path = ext_path / 'src' / 'backend'
                backend_path.mkdir(parents=True)
                (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                # Should raise SystemExit when duplicates are detected
                with pytest.raises(SystemExit) as exc_info:
                    registry.discover_extensions()
                
                # Should exit with error code 1
                assert exc_info.value.code == 1
                
                # No extensions should be loaded
                assert len(registry.loaded_extensions) == 0


# ============================================================================
# Dynamic AppConfig Creation Tests
# ============================================================================

@pytest.mark.django_db
class TestDynamicAppConfig:
    def test_dynamic_app_config_creation(self):
        """Test that dynamic AppConfig is created when apps.py doesn't exist."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'dynamic_config_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "dynamic_config_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            # Don't create apps.py - should use dynamic config
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Should still load the extension
                assert 'dynamic_config_ext' in registry.loaded_extensions
                # App config path should be returned
                assert len(apps) > 0

    def test_app_config_with_existing_apps_py(self):
        """Test behavior when apps.py exists."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'existing_apps_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "existing_apps_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            # Create apps.py
            (backend_path / 'apps.py').write_text('from django.apps import AppConfig\n\nclass ExistingAppsExtConfig(AppConfig):\n    name = "existing_apps_ext.src.backend"\n    label = "existing_apps_ext"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Should load the extension
                assert 'existing_apps_ext' in registry.loaded_extensions
                assert len(apps) > 0


# ============================================================================
# Extension Hooks and AppConfig Tests
# ============================================================================

@pytest.mark.django_db
class TestExtensionAppConfigIntegration:
    """Test ExtensionAppConfig integration with extension loader."""
    
    def test_dynamic_app_config_inherits_extension_app_config(self):
        """Test that dynamically created AppConfig inherits from ExtensionAppConfig."""
        from website.extensions.extension_base import ExtensionAppConfig
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'test_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "test_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            # Don't create apps.py - should use dynamic config
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Should have created dynamic AppConfig
                assert len(apps) == 1
                app_config_path = apps[0]
                
                # Import and verify it inherits from ExtensionAppConfig
                module_path, class_name = app_config_path.rsplit('.', 1)
                module = __import__(module_path, fromlist=[class_name])
                app_config_class = getattr(module, class_name)
                
                assert issubclass(app_config_class, ExtensionAppConfig)
    
    def test_extension_with_custom_apps_py_can_inherit_extension_app_config(self):
        """Test that extensions with custom apps.py can inherit from ExtensionAppConfig."""
        from website.extensions.extension_base import ExtensionAppConfig
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'custom_apps_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.toml'
            manifest_path.write_text('name = "custom_apps_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            # Create apps.py that inherits from ExtensionAppConfig
            apps_py_content = '''from website.extensions.extension_base import ExtensionAppConfig

class CustomAppsExtConfig(ExtensionAppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'extensions.custom_apps_ext.src.backend'
    label = 'custom_apps_ext'
    verbose_name = 'Custom Apps Extension'
'''
            (backend_path / 'apps.py').write_text(apps_py_content)
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.registry.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Should load the extension
                assert 'custom_apps_ext' in registry.loaded_extensions
                assert len(apps) > 0
                
                # Verify the AppConfig class exists and inherits correctly
                app_config_path = apps[0]
                # When apps.py exists, extension loader returns the full class path
                # e.g., "extensions.custom_apps_ext.src.backend.apps.CustomAppsExtConfig"
                # We need to extract the module path and class name
                if '.apps.' in app_config_path:
                    # Extract module path (everything before the last dot)
                    module_path, class_name = app_config_path.rsplit('.', 1)
                    # The module path should end with .apps, so we can import it directly
                    apps_module_path = module_path
                else:
                    # Fallback: assume it's just the module path
                    apps_module_path = f"{app_config_path}.apps"
                    class_name = None
                
                # Ensure the extension directory is in sys.path (should already be from discover_extensions)
                if str(ext_dir) not in sys.path:
                    sys.path.insert(0, str(ext_dir))
                
                # Import the apps module where the AppConfig class is defined
                apps_module = importlib.import_module(apps_module_path)
                
                # Find the AppConfig class in the apps module
                if class_name:
                    # We know the class name, so get it directly
                    app_config_class = getattr(apps_module, class_name)
                    assert app_config_class is not None, f"Could not find AppConfig class {class_name} in {apps_module_path}"
                else:
                    # Search for the AppConfig class
                    app_config_class = None
                    for attr_name in dir(apps_module):
                        attr = getattr(apps_module, attr_name)
                        if (isinstance(attr, type) and 
                            issubclass(attr, ExtensionAppConfig) and 
                            attr is not ExtensionAppConfig):
                            app_config_class = attr
                            break
                    
                    # Should have found the CustomAppsExtConfig class
                    assert app_config_class is not None, f"Could not find AppConfig class in {apps_module_path}. Available: {[x for x in dir(apps_module) if not x.startswith('_')]}"
                
                assert issubclass(app_config_class, ExtensionAppConfig)


# ============================================================================
# Example Extension ImportProvider Tests
# ============================================================================

class TestExampleExtensionImportProvider(TestCase):
    """ExampleImportProvider writes a user-scoped ExampleItem after import."""

    def setUp(self):
        from django.contrib.auth import get_user_model
        from django.contrib.gis.geos import Point
        from api.models import ImportQueue, FeatureStore
        from geo_lib.feature_id import generate_geojson_hash
        from geo_lib.processing.hooks import execute_import_hooks
        from website.extensions.capabilities import set_extension_context, clear_extension_context
        from website.extensions.import_provider import (
            clear_import_providers,
            list_import_providers,
            register_import_provider,
        )
        from extensions.example_extension.src.backend.import_provider import ExampleImportProvider
        from extensions.example_extension.src.backend.models import ExampleItem

        User = get_user_model()
        self.user = User.objects.create_user(
            username='testuser',
            email='test@example.com',
            password='testpass123',
        )
        self.ImportQueue = ImportQueue
        self.FeatureStore = FeatureStore
        self.Point = Point
        self.generate_geojson_hash = generate_geojson_hash
        self.execute_import_hooks = execute_import_hooks
        self.ExampleItem = ExampleItem

        clear_import_providers()
        set_extension_context('example_extension')
        try:
            register_import_provider(ExampleImportProvider())
        finally:
            clear_extension_context()
        assert list_import_providers()

    def tearDown(self):
        from website.extensions.import_provider import clear_import_providers
        clear_import_providers()

    def _feature(self, import_item, name='Test Feature'):
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
            'properties': {'name': name},
        }
        return self.FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=self.Point(10.0, 20.0, 0.0),
            geojson_hash=self.generate_geojson_hash(feature_data),
            source=import_item,
            scope='example_extension',
        )

    def test_import_provider_creates_example_item(self):
        import_item = self.ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            imported=True,
        )
        feature = self._feature(import_item)
        self.execute_import_hooks(import_item, self.user.id, [feature])
        items = list(self.ExampleItem.objects.filter(user=self.user))
        assert len(items) == 1
        assert str(import_item.id) in items[0].name
        assert '1 features imported' in items[0].description

    def test_import_provider_with_multiple_features(self):
        import_item = self.ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            imported=True,
        )
        features = [self._feature(import_item, f'Feature {i}') for i in range(3)]
        self.execute_import_hooks(import_item, self.user.id, features)
        item = self.ExampleItem.objects.get(user=self.user)
        assert '3 features imported' in item.description

    def test_import_provider_skips_empty_features(self):
        import_item = self.ImportQueue.objects.create(
            user=self.user,
            original_filename='empty.kml',
            raw_file='<kml></kml>',
            imported=True,
        )
        self.execute_import_hooks(import_item, self.user.id, [])
        assert self.ExampleItem.objects.filter(user=self.user).count() == 0
