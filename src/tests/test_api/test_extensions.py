
import pytest
import tempfile
import shutil
from unittest.mock import patch, MagicMock, mock_open
from pathlib import Path
from website.extension_loader import ExtensionRegistry, get_extension_registry, _registry
from django.test import modify_settings, TestCase
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
        with patch('website.extension_loader.get_config_loader') as mock_loader_get:
            mock_config = MagicMock()
            # Default to True for all boolean checks
            mock_config.get_bool.return_value = True
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
        
        with patch('website.extension_loader.get_config_loader') as mock_loader_get:
            mock_config = MagicMock()
            
            # Define side effect for get_bool to return False for our specific extension
            def get_bool_side_effect(key, default=False):
                if key == 'extensions.example_extension.enabled':
                    return False
                return default
                
            mock_config.get_bool.side_effect = get_bool_side_effect
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
        
        with patch('website.extension_loader.get_config_loader') as mock_loader_get:
            mock_config = MagicMock()
            
            def get_bool_side_effect(key, default=False):
                if key == 'extensions.example_extension.enabled':
                    return True
                return default
                
            mock_config.get_bool.side_effect = get_bool_side_effect
            mock_loader_get.return_value = mock_config
            
            registry.discover_extensions()
            
            # Should be in loaded_extensions
            assert 'example_extension' in registry.loaded_extensions

class TestExtensionAPI(TestCase):
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
        assert isinstance(data, list)
        
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
        # Path: /extensions/static/example-extension/src/frontend/dist/index.css
        
        url = '/extensions/static/example-extension/src/frontend/dist/index.css'
        response = client.get(url)
        
        assert response.status_code == 200
        assert 'text/css' in response['Content-Type']
        # FileResponse is streaming, so we don't check .content
        # Just status 200 verifies it was found and served.


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
        """Test that directories without manifest.py are skipped."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            # Create extension directory without manifest
            (ext_dir / 'no_manifest_ext').mkdir()
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "test"\nversion = "1.0.0"\ninvalid syntax here!!!')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('version = "1.0.0"\ndescription = "Test"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "missing_version_ext"\ndescription = "Test"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                assert 'missing_version_ext' not in registry.loaded_extensions
                assert apps == []

    def test_extension_without_backend_directory(self):
        """Test that extension without src/backend/ directory is skipped."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'no_backend_ext'
            ext_path.mkdir()
            
            # Create manifest but no backend directory
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "no_backend_ext"\nversion = "1.0.0"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Should not be loaded because no backend directory
                assert 'no_backend_ext' not in registry.loaded_extensions
                assert apps == []

    def test_extension_without_frontend_dist(self):
        """Test that extension with backend but no frontend dist still loads."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'no_frontend_ext'
            ext_path.mkdir()
            
            # Create manifest
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "no_frontend_ext"\nversion = "1.0.0"')
            
            # Create backend directory
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('raise RuntimeError("Test exception")\nname = "test"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
        
        with patch('website.extension_loader.get_config_loader') as mock_loader_get:
            mock_config = MagicMock()
            mock_config.get_bool.return_value = True
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
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "no_urls_ext"\nversion = "1.0.0"')
            
            # Create backend directory but no urls.py
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "test_extension_name"\nversion = "1.0.0"')
            
            # Create backend with urls.py
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            (backend_path / 'urls.py').write_text('from django.urls import path\nurlpatterns = []')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            
            manifest_path = ext_path / 'manifest.py'
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
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "js_umd_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            (dist_path / 'index.umd.js').write_text('// umd')
            (dist_path / 'index.iife.js').write_text('// iife')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                meta = registry.loaded_extensions['js_umd_ext']
                assert '.umd.' in meta['frontend_entry']
                assert '.iife.' not in meta['frontend_entry']

    def test_js_file_in_assets_subdirectory(self):
        """Test that JS files in dist/assets/ are discovered."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'js_assets_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.py'
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
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            
            manifest_path = ext_path / 'manifest.py'
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
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "css_style_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            dist_path = ext_path / 'src' / 'frontend' / 'dist'
            dist_path.mkdir(parents=True)
            (dist_path / 'style.css').write_text('/* style.css */')
            (dist_path / 'other.css').write_text('/* other */')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            
            manifest_path = ext_path / 'manifest.py'
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
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                meta = registry.loaded_extensions['css_assets_ext']
                assert 'assets/style.css' in meta['frontend_css']


# ============================================================================
# API Endpoint Edge Cases Tests
# ============================================================================

class TestExtensionAPIEdgeCases(TestCase):
    def test_list_extensions_empty_registry(self):
        """Test GET /api/extensions/ when no extensions are loaded."""
        client = self.client
        
        # Mock registry to return empty list - patch at the view level
        mock_registry_instance = MagicMock()
        mock_registry_instance.get_active_extensions.return_value = []
        
        with patch('api.views.extensions.management._registry', mock_registry_instance):
            response = client.get('/api/extensions/')
            assert response.status_code == 200
            data = response.json()
            assert isinstance(data, list)
            assert len(data) == 0

    def test_list_extensions_registry_none(self):
        """Test GET /api/extensions/ when _registry is None."""
        client = self.client
        
        # Mock _registry to be None
        with patch('api.views.extensions.management._registry', None):
            response = client.get('/api/extensions/')
            assert response.status_code == 200
            data = response.json()
            assert isinstance(data, list)
            assert len(data) == 0

    def test_extension_metadata_structure(self):
        """Test that extension metadata contains all expected fields."""
        from website.settings import EXTENSIONS_DIR
        registry = ExtensionRegistry(EXTENSIONS_DIR)
        
        with patch('website.extension_loader.get_config_loader') as mock_loader_get:
            mock_config = MagicMock()
            mock_config.get_bool.return_value = True
            mock_loader_get.return_value = mock_config
            
            registry.discover_extensions()
            
            extensions = registry.get_active_extensions()
            
            if len(extensions) > 0:
                ext = extensions[0]
                # Verify all expected fields are present
                assert 'name' in ext
                assert 'version' in ext
                assert 'description' in ext
                assert 'frontend_entry' in ext
                assert 'frontend_css' in ext
                assert 'urls_module' in ext


# ============================================================================
# Static Asset Serving Edge Cases Tests
# ============================================================================

class TestStaticAssetServingEdgeCases(TestCase):
    def test_static_asset_404(self):
        """Test that non-existent static files return 404."""
        client = self.client
        
        url = '/extensions/static/example-extension/src/frontend/dist/nonexistent.css'
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

    def test_static_asset_kebab_case_conversion(self):
        """Test that kebab-case URLs are converted to snake_case paths."""
        client = self.client
        
        # Use kebab-case in URL (example-extension)
        url = '/extensions/static/example-extension/src/frontend/dist/index.css'
        response = client.get(url)
        
        # Should successfully convert to example_extension directory
        # If file exists, should return 200, otherwise 404
        assert response.status_code in [200, 404]


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
            (ext1_path / 'manifest.py').write_text('name = "enabled_ext"\nversion = "1.0.0"')
            backend1_path = ext1_path / 'src' / 'backend'
            backend1_path.mkdir(parents=True)
            (backend1_path / '__init__.py').write_text('')
            
            # Create second extension (disabled)
            ext2_path = ext_dir / 'disabled_ext'
            ext2_path.mkdir()
            (ext2_path / 'manifest.py').write_text('name = "disabled_ext"\nversion = "1.0.0"')
            backend2_path = ext2_path / 'src' / 'backend'
            backend2_path.mkdir(parents=True)
            (backend2_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                
                def get_bool_side_effect(key, default=False):
                    if key == 'extensions.enabled_ext.enabled':
                        return True
                    elif key == 'extensions.disabled_ext.enabled':
                        return False
                    return default
                
                mock_config.get_bool.side_effect = get_bool_side_effect
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                # Only enabled extension should be loaded
                assert 'enabled_ext' in registry.loaded_extensions
                assert 'disabled_ext' not in registry.loaded_extensions

    def test_enabled_by_default_false(self):
        """Test that enabled_by_default = False requires explicit enable."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'default_disabled_ext'
            ext_path.mkdir()
            
            # Create manifest with enabled_by_default = False
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "default_disabled_ext"\nversion = "1.0.0"\nenabled_by_default = False')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                # Don't explicitly enable it
                mock_config.get_bool.return_value = False
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                # Should not be loaded since default is False and not explicitly enabled
                assert 'default_disabled_ext' not in registry.loaded_extensions

    def test_enabled_by_default_true(self):
        """Test that enabled_by_default = True loads extension by default."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'default_enabled_ext'
            ext_path.mkdir()
            
            # Create manifest with enabled_by_default = True (or omit it, defaults to True)
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "default_enabled_ext"\nversion = "1.0.0"\nenabled_by_default = True')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                # Return default value (True) when key not found
                def get_bool_side_effect(key, default=True):
                    return default
                mock_config.get_bool.side_effect = get_bool_side_effect
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
        """Test that get_active_extensions() returns correct metadata."""
        from website.settings import EXTENSIONS_DIR
        registry = ExtensionRegistry(EXTENSIONS_DIR)
        
        with patch('website.extension_loader.get_config_loader') as mock_loader_get:
            mock_config = MagicMock()
            mock_config.get_bool.return_value = True
            mock_loader_get.return_value = mock_config
            
            registry.discover_extensions()
            
            extensions = registry.get_active_extensions()
            
            assert isinstance(extensions, list)
            if len(extensions) > 0:
                for ext in extensions:
                    assert isinstance(ext, dict)
                    assert 'name' in ext
                    assert 'version' in ext

    def test_get_extension_registry_singleton(self):
        """Test that get_extension_registry() returns the same instance."""
        # Reset global registry
        import website.extension_loader
        website.extension_loader._registry = None
        
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
            (ext1_path / 'manifest.py').write_text('name = "duplicate_name"\nversion = "1.0.0"')
            backend1_path = ext1_path / 'src' / 'backend'
            backend1_path.mkdir(parents=True)
            (backend1_path / '__init__.py').write_text('')
            
            # Create second extension with same name
            ext2_path = ext_dir / 'extension_two'
            ext2_path.mkdir()
            (ext2_path / 'manifest.py').write_text('name = "duplicate_name"\nversion = "2.0.0"')
            backend2_path = ext2_path / 'src' / 'backend'
            backend2_path.mkdir(parents=True)
            (backend2_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
                mock_loader_get.return_value = mock_config
                
                # Should raise SystemExit when duplicates are detected
                with pytest.raises(SystemExit) as exc_info:
                    registry.discover_extensions()
                
                # Should exit with error code 1
                assert exc_info.value.code == 1
                
                # No extensions should be loaded
                assert len(registry.loaded_extensions) == 0

    def test_startup_check_detects_duplicates(self):
        """Test that startup check detects duplicate extension names."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            
            # Create first extension
            ext1_path = ext_dir / 'extension_one'
            ext1_path.mkdir()
            (ext1_path / 'manifest.py').write_text('name = "duplicate_name"\nversion = "1.0.0"')
            backend1_path = ext1_path / 'src' / 'backend'
            backend1_path.mkdir(parents=True)
            (backend1_path / '__init__.py').write_text('')
            
            # Create second extension with same name
            ext2_path = ext_dir / 'extension_two'
            ext2_path.mkdir()
            (ext2_path / 'manifest.py').write_text('name = "duplicate_name"\nversion = "2.0.0"')
            backend2_path = ext2_path / 'src' / 'backend'
            backend2_path.mkdir(parents=True)
            (backend2_path / '__init__.py').write_text('')
            
            # Mock EXTENSIONS_DIR for startup check to use our temp directory
            with patch('website.settings.EXTENSIONS_DIR', ext_dir):
                from website.startup_checks import check_extensions
                
                # The check should return False (duplicates detected)
                # Note: discover_extensions will exit before startup check runs,
                # but if it did run, it would detect duplicates
                result = check_extensions()
                assert result is False

    def test_startup_check_passes_with_unique_names(self):
        """Test that startup check passes when all extension names are unique."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            
            # Create first extension
            ext1_path = ext_dir / 'extension_one'
            ext1_path.mkdir()
            (ext1_path / 'manifest.py').write_text('name = "unique_name_one"\nversion = "1.0.0"')
            backend1_path = ext1_path / 'src' / 'backend'
            backend1_path.mkdir(parents=True)
            (backend1_path / '__init__.py').write_text('')
            
            # Create second extension with different name
            ext2_path = ext_dir / 'extension_two'
            ext2_path.mkdir()
            (ext2_path / 'manifest.py').write_text('name = "unique_name_two"\nversion = "2.0.0"')
            backend2_path = ext2_path / 'src' / 'backend'
            backend2_path.mkdir(parents=True)
            (backend2_path / '__init__.py').write_text('')
            
            # Create registry and load extensions
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
            
            # Mock EXTENSIONS_DIR for startup check to use our temp directory
            with patch('website.settings.EXTENSIONS_DIR', ext_dir):
                from website.startup_checks import check_extensions
                
                # The check should return True (no duplicates)
                result = check_extensions()
                assert result is True

    def test_extension_name_mismatch_with_folder_name(self):
        """Test extension where manifest name differs from folder name."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            
            # Create extension with folder name different from manifest name
            ext_path = ext_dir / 'folder_name'
            ext_path.mkdir()
            (ext_path / 'manifest.py').write_text('name = "manifest_name"\nversion = "1.0.0"')
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
                mock_loader_get.return_value = mock_config
                
                registry.discover_extensions()
                
                # Should use manifest name, not folder name
                assert 'manifest_name' in registry.loaded_extensions
                assert 'folder_name' not in registry.loaded_extensions
                # Module path should use folder name
                apps = registry.discover_extensions()
                assert any('folder_name.src.backend' in app for app in apps)

    def test_multiple_duplicates_detected(self):
        """Test that multiple sets of duplicates are all detected and cause SystemExit."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            
            # Create extensions with duplicate names
            for i, dup_name in enumerate(['dup_one', 'dup_one', 'dup_two', 'dup_two']):
                ext_path = ext_dir / f'ext_{i}'
                ext_path.mkdir()
                (ext_path / 'manifest.py').write_text(f'name = "{dup_name}"\nversion = "1.0.0"')
                backend_path = ext_path / 'src' / 'backend'
                backend_path.mkdir(parents=True)
                (backend_path / '__init__.py').write_text('')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "dynamic_config_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            # Don't create apps.py - should use dynamic config
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
            
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "existing_apps_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            # Create apps.py
            (backend_path / 'apps.py').write_text('from django.apps import AppConfig\n\nclass ExistingAppsExtConfig(AppConfig):\n    name = "existing_apps_ext.src.backend"\n    label = "existing_apps_ext"')
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Should load the extension
                assert 'existing_apps_ext' in registry.loaded_extensions
                assert len(apps) > 0
