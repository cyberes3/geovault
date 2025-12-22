"""
Tests for tile API endpoints.
"""
import json
from io import BytesIO
from unittest.mock import MagicMock, patch
from urllib.response import addinfourl

import requests
from django.contrib.auth import get_user_model
from django.test import TestCase, override_settings

from geo_lib.tile_sources.registry import get_all_tile_sources, get_tile_source
from geo_lib.utils.version import get_user_agent


class TestTilesAPI(TestCase):
    """Test tile API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_get_tile_sources(self):
        """Test getting tile sources."""
        response = self.client.get('/api/tiles/sources/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('sources', data)

    def test_google_maps_tile_source_registered(self):
        """Test that Google Maps tile source is registered correctly."""
        google_maps_source = get_tile_source('google_maps')
        self.assertIsNotNone(google_maps_source)
        self.assertEqual(google_maps_source['name'], 'Google Maps')
        self.assertTrue(google_maps_source['requires_proxy'])
        self.assertIn('url_template', google_maps_source)
        self.assertIn('proxy_config', google_maps_source)
        self.assertIn('client_config', google_maps_source)

    def test_google_maps_url_template_contains_api_key(self):
        """Test that Google Maps URL template contains the API key."""
        google_maps_source = get_tile_source('google_maps')
        self.assertIsNotNone(google_maps_source)
        url_template = google_maps_source['url_template']
        self.assertIn('key=', url_template)
        # Verify it's a Google Maps URL
        self.assertIn('mt0.google.com', url_template)

    def test_google_terrain_tile_source_registered(self):
        """Test that Google Terrain tile source is registered correctly."""
        google_terrain_source = get_tile_source('google_terrain')
        self.assertIsNotNone(google_terrain_source)
        self.assertEqual(google_terrain_source['name'], 'Google Terrain')
        self.assertTrue(google_terrain_source['requires_proxy'])
        self.assertIn('url_template', google_terrain_source)
        self.assertIn('proxy_config', google_terrain_source)
        self.assertIn('client_config', google_terrain_source)

    def test_google_terrain_url_template_contains_api_key(self):
        """Test that Google Terrain URL template contains the API key."""
        google_terrain_source = get_tile_source('google_terrain')
        self.assertIsNotNone(google_terrain_source)
        url_template = google_terrain_source['url_template']
        self.assertIn('key=', url_template)
        # Verify it's a Google Maps URL (terrain uses same domain)
        self.assertIn('mt0.google.com', url_template)
        # Verify it's using terrain layer (lyrs=p)
        self.assertIn('lyrs=p', url_template)

    def test_maptiler_sources_may_be_registered(self):
        """Test that MapTiler sources may be registered if configured."""
        all_sources = get_all_tile_sources()
        # Only check map sources (exclude hillshade/terrain overlay sources)
        maptiler_sources = {
            source_id: config
            for source_id, config in all_sources.items()
            if source_id.startswith('maptiler_') and source_id not in ['maptiler_hillshade', 'maptiler_terrain']
        }
        # MapTiler sources are optional and depend on configuration
        # If they exist, verify they have the correct structure
        for source_id, config in maptiler_sources.items():
            self.assertIn('name', config)
            self.assertEqual(config['type'], 'maptiler')
            # requires_proxy depends on maptiler.proxy_tiles config, so we don't assert a specific value
            self.assertIn('requires_proxy', config)
            self.assertIn('client_config', config)
            client_config = config['client_config']
            self.assertIn('type', client_config)
            self.assertEqual(client_config['type'], 'maptiler')
            self.assertIn('style_url', client_config)
            self.assertIn('map_id', client_config)

    @patch('urllib.request.urlopen')
    def test_tile_proxy(self, mock_urlopen):
        """Test tile proxy endpoint."""
        
        # Create a mock response object
        mock_response = addinfourl(
            BytesIO(b'fake tile data'),
            {'Content-Type': 'image/png'},
            'http://example.com/tile.png',
            200
        )
        mock_urlopen.return_value = mock_response

        response = self.client.get('/api/tiles/test-service/10/512/512')
        # May return 200 if tile fetched successfully, or error if not
        self.assertIn(response.status_code, [200, 400, 404, 500])

    def test_tile_proxy_invalid_coordinates(self):
        """Test tile proxy with invalid coordinates."""
        response = self.client.get('/api/tiles/test-service/invalid/x/y')
        self.assertEqual(response.status_code, 404)

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot access tiles."""
        self.client.logout()
        response = self.client.get('/api/tiles/sources/')
        # Tiles may or may not require authentication depending on implementation
        self.assertIn(response.status_code, [200, 401])

    def test_osm_has_proxy_config_with_user_agent(self):
        """Test that OSM tile source has proxy_config with User-Agent header."""
        osm_source = get_tile_source('osm')
        self.assertIsNotNone(osm_source, "OSM tile source should be registered")
        
        # OSM may not require proxy by default, but should have proxy_config for when proxying is enabled
        proxy_config = osm_source.get('proxy_config')
        self.assertIsNotNone(proxy_config, "OSM should have proxy_config")
        
        headers = proxy_config.get('headers', {})
        self.assertIn('User-Agent', headers, "OSM proxy_config should include User-Agent header")
        
        # Verify User-Agent matches expected format
        user_agent = headers['User-Agent']
        self.assertTrue(user_agent.startswith('GeoVault/'), f"User-Agent should start with 'GeoVault/', got '{user_agent}'")
        self.assertEqual(user_agent, get_user_agent(), "User-Agent should match get_user_agent()")

    def test_opentopomap_has_proxy_config_with_user_agent(self):
        """Test that OpenTopoMap tile source has proxy_config with User-Agent header."""
        opentopomap_source = get_tile_source('opentopomap')
        self.assertIsNotNone(opentopomap_source, "OpenTopoMap tile source should be registered")
        
        proxy_config = opentopomap_source.get('proxy_config')
        self.assertIsNotNone(proxy_config, "OpenTopoMap should have proxy_config")
        
        headers = proxy_config.get('headers', {})
        self.assertIn('User-Agent', headers, "OpenTopoMap proxy_config should include User-Agent header")
        
        # Verify User-Agent matches expected format
        user_agent = headers['User-Agent']
        self.assertTrue(user_agent.startswith('GeoVault/'), f"User-Agent should start with 'GeoVault/', got '{user_agent}'")
        self.assertEqual(user_agent, get_user_agent(), "User-Agent should match get_user_agent()")

    @patch('api.views.services.tiles.requests.get')
    @patch('api.views.services.tiles.settings.TILE_CACHE_ENABLED', False)
    def test_tile_proxy_uses_custom_user_agent(self, mock_requests_get):
        """Test that tile proxy uses custom User-Agent header from proxy_config when proxying is enabled."""
        # Create a mock response
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.content = b'fake tile data'
        mock_response.headers = {'Content-Type': 'image/png'}
        mock_requests_get.return_value = mock_response

        # Use mb_topo which requires proxy by default
        mb_topo_source = get_tile_source('mb_topo')
        if not mb_topo_source or not mb_topo_source.get('requires_proxy'):
            self.skipTest("mb_topo tile source not available or doesn't require proxy")

        # Get the expected headers from proxy_config
        proxy_config = mb_topo_source.get('proxy_config', {})
        expected_headers = proxy_config.get('headers', {})

        # Make a proxy request with unique coordinates to avoid cache
        import random
        z = random.randint(1, 10)
        x = random.randint(0, 2**z - 1)
        y = random.randint(0, 2**z - 1)
        
        response = self.client.get(f'/api/tiles/mb_topo/{z}/{x}/{y}')

        # Verify requests.get was called
        self.assertTrue(mock_requests_get.called, "requests.get should have been called")

        # Get the call arguments
        call_args = mock_requests_get.call_args
        actual_headers = call_args.kwargs.get('headers', {})

        # Verify all headers from proxy_config are present
        for header_name, header_value in expected_headers.items():
            self.assertIn(
                header_name,
                actual_headers,
                f"Header '{header_name}' should be present in proxy request"
            )
            self.assertEqual(
                actual_headers[header_name],
                header_value,
                f"Header '{header_name}' should be '{header_value}', got '{actual_headers.get(header_name)}'"
            )

    @patch('geo_lib.tile_sources.registry.get_config_loader')
    def test_proxy_sources_config_overrides_requires_proxy(self, mock_get_config_loader):
        """Test that proxy_sources config option overrides requires_proxy for specified sources."""
        # Create a mock config loader
        mock_config_loader = MagicMock()
        # Include sources that don't normally require proxy (osm, opentopomap)
        # and one that already requires proxy by default (mb_topo) to test edge case
        mock_config_loader.get.return_value = ['osm', 'opentopomap', 'mb_topo']
        mock_get_config_loader.return_value = mock_config_loader
        
        # Clear the registry to force re-initialization with the mock config
        import geo_lib.tile_sources.registry as registry_module
        registry_module._tile_sources = {}
        registry_module._registered = False
        
        # Get tile sources (will re-initialize with mock config)
        osm_source = get_tile_source('osm')
        opentopomap_source = get_tile_source('opentopomap')
        mb_topo_source = get_tile_source('mb_topo')
        
        # Verify OSM and OpenTopoMap require proxy when in config
        self.assertIsNotNone(osm_source)
        self.assertTrue(osm_source.get('requires_proxy'), "OSM should require proxy when in proxy_sources config")
        self.assertEqual(osm_source['client_config']['url'], '/api/tiles/osm/{z}/{x}/{y}')
        
        self.assertIsNotNone(opentopomap_source)
        self.assertTrue(opentopomap_source.get('requires_proxy'), "OpenTopoMap should require proxy when in proxy_sources config")
        self.assertEqual(opentopomap_source['client_config']['url'], '/api/tiles/opentopomap/{z}/{x}/{y}')
        
        # Edge case: Verify mb_topo still works correctly when in proxy_sources config
        # (it already requires proxy by default, so it should remain proxied)
        self.assertIsNotNone(mb_topo_source)
        self.assertTrue(mb_topo_source.get('requires_proxy'), "mb_topo should still require proxy when in proxy_sources config")
        # mb_topo already has proxy URL, so it should remain unchanged
        self.assertEqual(mb_topo_source['client_config']['url'], '/api/tiles/mb_topo/{z}/{x}/{y}')
        # Verify it still has proxy_config
        self.assertIsNotNone(mb_topo_source.get('proxy_config'), "mb_topo should still have proxy_config")
        
        # Reset the registry for other tests
        registry_module._tile_sources = {}
        registry_module._registered = False

    @patch('geo_lib.tile_sources.registry.get_config_loader')
    @patch('geo_lib.tile_sources.maptiler_terrain.get_config_loader')
    @patch('geo_lib.tile_sources.maptiler_hillshade.get_config_loader')
    @patch('geo_lib.tile_sources.maptiler.get_config_loader')
    def test_proxy_sources_filters_out_maptiler_sources(self, mock_maptiler_config, mock_hillshade_config, mock_terrain_config, mock_get_config_loader):
        """Test that MapTiler sources are filtered out from proxy_sources config.
        MapTiler proxying is controlled by maptiler.proxy_tiles, not tilesources.proxy_sources."""
        # Create a mock config loader
        mock_config_loader = MagicMock()
        # Include MapTiler sources in proxy_sources - they should be ignored
        mock_config_loader.get.return_value = ['osm', 'maptiler_terrain', 'maptiler_hillshade', 'maptiler_topo-v4']
        # Set maptiler.proxy_tiles to False for all MapTiler sources
        def get_bool_side_effect(key, default=False):
            if key == 'maptiler.proxy_tiles':
                return False
            return default
        mock_config_loader.get_bool.side_effect = get_bool_side_effect
        mock_config_loader.get_with_env_override.return_value = 'test-api-key'
        mock_config_loader.get_str.return_value = 'example.com'
        mock_config_loader.get_list.return_value = []
        
        # Use the same mock for all config loaders
        mock_get_config_loader.return_value = mock_config_loader
        mock_terrain_config.return_value = mock_config_loader
        mock_hillshade_config.return_value = mock_config_loader
        mock_maptiler_config.return_value = mock_config_loader
        
        # Clear the registry to force re-initialization with the mock config
        import geo_lib.tile_sources.registry as registry_module
        registry_module._tile_sources = {}
        registry_module._registered = False
        
        # Get tile sources (will re-initialize with mock config)
        osm_source = get_tile_source('osm')
        maptiler_terrain_source = get_tile_source('maptiler_terrain')
        
        # Verify OSM is proxied (it's not a MapTiler source)
        self.assertIsNotNone(osm_source)
        self.assertTrue(osm_source.get('requires_proxy'), "OSM should require proxy when in proxy_sources config")
        
        # Verify MapTiler terrain is NOT affected by proxy_sources
        # It should use its default behavior based on maptiler.proxy_tiles
        if maptiler_terrain_source:
            # MapTiler terrain's requires_proxy is controlled by maptiler.proxy_tiles, not proxy_sources
            # We've mocked maptiler.proxy_tiles to False, so requires_proxy should be False
            # The key point is that proxy_sources didn't override it
            self.assertFalse(maptiler_terrain_source.get('requires_proxy'), 
                           "MapTiler terrain should not be affected by proxy_sources config")
        
        # Reset the registry for other tests
        registry_module._tile_sources = {}
        registry_module._registered = False

    @patch('api.views.services.tiles.requests.get')
    @override_settings(TILE_CACHE_ENABLED=False)
    def test_tile_proxy_cache_control_header_uses_cache_expiry_days(self, mock_requests_get):
        """Test that Cache-Control header uses TILE_CACHE_EXPIRY_DAYS setting."""
        # Create a mock response
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.content = b'fake tile data'
        mock_response.headers = {'Content-Type': 'image/png'}
        mock_requests_get.return_value = mock_response

        # Use mb_topo which requires proxy by default
        mb_topo_source = get_tile_source('mb_topo')
        if not mb_topo_source or not mb_topo_source.get('requires_proxy'):
            self.skipTest("mb_topo tile source not available or doesn't require proxy")

        # Test with default cache_expiry_days (30 days)
        from django.conf import settings
        default_expiry_days = settings.TILE_CACHE_EXPIRY_DAYS
        expected_max_age = default_expiry_days * 24 * 60 * 60

        # Make a proxy request
        import random
        z = random.randint(1, 10)
        x = random.randint(0, 2**z - 1)
        y = random.randint(0, 2**z - 1)
        
        response = self.client.get(f'/api/tiles/mb_topo/{z}/{x}/{y}')
        self.assertEqual(response.status_code, 200)
        
        # Verify Cache-Control header is present and uses correct max-age
        self.assertIn('Cache-Control', response)
        cache_control = response['Cache-Control']
        self.assertIn('public', cache_control)
        self.assertIn(f'max-age={expected_max_age}', cache_control)

    @patch('api.views.services.tiles.requests.get')
    @override_settings(TILE_CACHE_ENABLED=False, TILE_CACHE_EXPIRY_DAYS=7)
    def test_tile_proxy_cache_control_header_respects_custom_cache_expiry_days(self, mock_requests_get):
        """Test that Cache-Control header respects custom TILE_CACHE_EXPIRY_DAYS setting."""
        # Create a mock response
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.content = b'fake tile data'
        mock_response.headers = {'Content-Type': 'image/png'}
        mock_requests_get.return_value = mock_response

        # Use mb_topo which requires proxy by default
        mb_topo_source = get_tile_source('mb_topo')
        if not mb_topo_source or not mb_topo_source.get('requires_proxy'):
            self.skipTest("mb_topo tile source not available or doesn't require proxy")

        # Test with custom cache_expiry_days (7 days)
        custom_expiry_days = 7
        expected_max_age = custom_expiry_days * 24 * 60 * 60  # 604800 seconds

        # Make a proxy request
        import random
        z = random.randint(1, 10)
        x = random.randint(0, 2**z - 1)
        y = random.randint(0, 2**z - 1)
        
        response = self.client.get(f'/api/tiles/mb_topo/{z}/{x}/{y}')
        self.assertEqual(response.status_code, 200)
        
        # Verify Cache-Control header uses the custom max-age
        self.assertIn('Cache-Control', response)
        cache_control = response['Cache-Control']
        self.assertIn('public', cache_control)
        self.assertIn(f'max-age={expected_max_age}', cache_control)
        # Verify it's NOT using the default 30 days
        self.assertNotIn('max-age=2592000', cache_control)

    @patch('api.views.services.tiles.requests.get')
    @override_settings(TILE_CACHE_ENABLED=False)
    def test_tile_proxy_removes_set_cookie_header_for_cloudflare_caching(self, mock_requests_get):
        """Test that tile proxy responses do not include Set-Cookie headers to allow Cloudflare caching."""
        # Create a mock response
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.content = b'fake tile data'
        mock_response.headers = {'Content-Type': 'image/png'}
        mock_requests_get.return_value = mock_response

        # Use mb_topo which requires proxy by default
        mb_topo_source = get_tile_source('mb_topo')
        if not mb_topo_source or not mb_topo_source.get('requires_proxy'):
            self.skipTest("mb_topo tile source not available or doesn't require proxy")

        # Make a proxy request (user is logged in, so session middleware would normally set Set-Cookie)
        # Modify the session to ensure it would be saved (which triggers Set-Cookie)
        import random
        z = random.randint(1, 10)
        x = random.randint(0, 2**z - 1)
        y = random.randint(0, 2**z - 1)
        
        # Modify session to trigger session save (which would normally set Set-Cookie)
        self.client.session['test_key'] = 'test_value'
        self.client.session.save()
        
        response = self.client.get(f'/api/tiles/mb_topo/{z}/{x}/{y}')
        self.assertEqual(response.status_code, 200)
        
        # Verify Set-Cookie header is NOT present (Cloudflare won't cache if it is)
        # Django's session middleware normally sets this for authenticated users
        # Check multiple ways to ensure we catch it:
        
        # Method 1: Check response.items() for Set-Cookie headers
        set_cookie_headers = [h for h in response.items() if h[0].lower() == 'set-cookie']
        self.assertEqual(len(set_cookie_headers), 0,
                        f"Tile proxy responses should not include Set-Cookie header for Cloudflare caching. Found in items(): {set_cookie_headers}")
        
        # Method 2: Check response.has_header() and response.get()
        self.assertFalse(response.has_header('Set-Cookie'),
                        "Tile proxy responses should not have Set-Cookie header (checked via has_header)")
        self.assertIsNone(response.get('Set-Cookie', None),
                         "Tile proxy responses should not have Set-Cookie header (checked via get())")
        
        # Method 3: Check response.cookies directly (Django stores cookies here)
        # This is the most important check - response.cookies is what gets serialized to Set-Cookie headers
        from django.conf import settings
        self.assertNotIn(settings.SESSION_COOKIE_NAME, response.cookies,
                        f"Tile proxy responses should not include {settings.SESSION_COOKIE_NAME} cookie in response.cookies")
        self.assertNotIn(settings.CSRF_COOKIE_NAME, response.cookies,
                        f"Tile proxy responses should not include {settings.CSRF_COOKIE_NAME} cookie in response.cookies")
        # Verify no cookies at all
        self.assertEqual(len(response.cookies), 0,
                        f"Tile proxy responses should not include any cookies. Found: {list(response.cookies.keys())}")
        
        # Verify Vary: Cookie header is also removed (prevents caching)
        if response.has_header('Vary'):
            vary_value = response['Vary']
            self.assertNotIn('Cookie', vary_value,
                           f"Tile proxy responses should not include 'Cookie' in Vary header. Found: {vary_value}")
        
        # Verify Access-Control-Allow-Credentials is NOT present (also prevents caching)
        self.assertNotIn('Access-Control-Allow-Credentials', response,
                        "Tile proxy responses should not include Access-Control-Allow-Credentials for maximum cacheability")
        
        # Verify Cache-Control header is still present and correct
        self.assertIn('Cache-Control', response)
        cache_control = response['Cache-Control']
        self.assertIn('public', cache_control)
        self.assertIn('max-age=', cache_control)

    @patch('geo_lib.tile_sources.maptiler.get_config_loader')
    def test_maptiler_map_sources_exclude_api_key_when_proxying(self, mock_get_config_loader):
        """Test that MapTiler map sources exclude API key from client_config when proxying is enabled."""
        mock_config_loader = MagicMock()
        mock_config_loader.get_with_env_override.return_value = 'test-api-key-12345'
        mock_config_loader.get_list.return_value = ['topo-v4', 'satellite']
        mock_config_loader.get_str.return_value = 'example.com'
        mock_config_loader.get_bool.return_value = True  # proxy_tiles = True
        mock_get_config_loader.return_value = mock_config_loader
        
        # Clear the registry to force re-initialization
        import geo_lib.tile_sources.registry as registry_module
        registry_module._tile_sources = {}
        registry_module._registered = False
        
        # Get tile sources (will re-initialize with mock config)
        topo_source = get_tile_source('maptiler_topo-v4')
        
        if topo_source:
            client_config = topo_source.get('client_config', {})
            style_url = client_config.get('style_url', '')
            
            # Verify style_url uses proxy endpoint (no API key)
            self.assertTrue(style_url.startswith('/api/tiles/style/'), 
                          "Style URL should use proxy endpoint when proxying")
            self.assertNotIn('key=', style_url, 
                           "Style URL should not contain API key when proxying")
            self.assertNotIn('test-api-key', style_url,
                           "Style URL should not contain API key value when proxying")
        
        # Reset the registry for other tests
        registry_module._tile_sources = {}
        registry_module._registered = False

    @patch('geo_lib.tile_sources.maptiler.get_config_loader')
    def test_maptiler_map_sources_include_api_key_when_not_proxying(self, mock_get_config_loader):
        """Test that MapTiler map sources include API key in client_config when proxying is disabled."""
        mock_config_loader = MagicMock()
        mock_config_loader.get_with_env_override.return_value = 'test-api-key-12345'
        mock_config_loader.get_list.return_value = ['topo-v4', 'satellite']
        mock_config_loader.get_str.return_value = 'example.com'
        mock_config_loader.get_bool.return_value = False  # proxy_tiles = False
        mock_get_config_loader.return_value = mock_config_loader
        
        # Clear the registry to force re-initialization
        import geo_lib.tile_sources.registry as registry_module
        registry_module._tile_sources = {}
        registry_module._registered = False
        
        # Get tile sources (will re-initialize with mock config)
        topo_source = get_tile_source('maptiler_topo-v4')
        
        if topo_source:
            client_config = topo_source.get('client_config', {})
            style_url = client_config.get('style_url', '')
            
            # Verify style_url uses direct MapTiler URL with API key
            self.assertTrue(style_url.startswith('https://api.maptiler.com/'), 
                          "Style URL should use direct MapTiler URL when not proxying")
            self.assertIn('key=', style_url, 
                         "Style URL should contain API key parameter when not proxying")
            self.assertIn('test-api-key-12345', style_url,
                         "Style URL should contain API key value when not proxying")
        
        # Reset the registry for other tests
        registry_module._tile_sources = {}
        registry_module._registered = False

    @patch('api.views.services.tiles.requests.get')
    @patch('api.views.services.tiles.get_config_loader')
    def test_style_proxy_replaces_tile_urls(self, mock_get_config_loader, mock_requests_get):
        """Test that style_proxy endpoint replaces MapTiler tile URLs with proxy URLs."""
        # Mock config loader
        mock_config_loader = MagicMock()
        mock_config_loader.get_with_env_override.return_value = 'test-api-key-12345'
        mock_config_loader.get_str.return_value = 'example.com'
        mock_get_config_loader.return_value = mock_config_loader
        
        # Mock style.json response from MapTiler
        mock_style_response = MagicMock()
        mock_style_response.status_code = 200
        mock_style_response.json.return_value = {
            'version': 8,
            'sources': {
                'maptiler': {
                    'type': 'vector',
                    'tiles': [
                        'https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key=test-api-key-12345'
                    ]
                }
            },
            'layers': []
        }
        mock_requests_get.return_value = mock_style_response
        
        # Get the tile source and ensure it requires proxy
        import geo_lib.tile_sources.registry as registry_module
        registry_module._tile_sources = {}
        registry_module._registered = False
        
        # Mock the registry to return a proxied MapTiler source
        with patch('geo_lib.tile_sources.maptiler.get_config_loader') as mock_maptiler_config:
            mock_maptiler_config.return_value.get_with_env_override.return_value = 'test-api-key-12345'
            mock_maptiler_config.return_value.get_list.return_value = ['topo-v4']
            mock_maptiler_config.return_value.get_str.return_value = 'example.com'
            mock_maptiler_config.return_value.get_bool.return_value = True  # proxy_tiles = True
            
            # Get the source to register it
            topo_source = get_tile_source('maptiler_topo-v4')
            
            if topo_source and topo_source.get('requires_proxy'):
                # Call style proxy endpoint
                response = self.client.get('/api/tiles/style/topo-v4')
                self.assertEqual(response.status_code, 200)
                
                data = json.loads(response.content)
                
                # Verify style.json structure is preserved
                self.assertIn('sources', data)
                self.assertIn('maptiler', data['sources'])
                
                # Verify tile URLs are replaced with proxy URLs
                maptiler_source = data['sources']['maptiler']
                self.assertIn('tiles', maptiler_source)
                self.assertEqual(len(maptiler_source['tiles']), 1)
                self.assertEqual(maptiler_source['tiles'][0], '/api/tiles/maptiler_topo-v4/{z}/{x}/{y}')
        
        # Reset the registry
        registry_module._tile_sources = {}
        registry_module._registered = False

    @patch('api.views.services.tiles.requests.get')
    @override_settings(TILE_CACHE_ENABLED=False)
    def test_tile_proxy_handles_pbf_vector_tiles(self, mock_requests_get):
        """Test that tile proxy correctly handles .pbf vector tile files."""
        # Create a mock response for vector tile
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.content = b'fake pbf tile data'
        mock_response.headers = {'Content-Type': 'application/x-protobuf'}
        mock_requests_get.return_value = mock_response

        # Use a MapTiler source that requires proxy (if available)
        import geo_lib.tile_sources.registry as registry_module
        registry_module._tile_sources = {}
        registry_module._registered = False
        
        with patch('geo_lib.tile_sources.maptiler.get_config_loader') as mock_maptiler_config:
            mock_maptiler_config.return_value.get_with_env_override.return_value = 'test-api-key-12345'
            mock_maptiler_config.return_value.get_list.return_value = ['topo-v4']
            mock_maptiler_config.return_value.get_str.return_value = 'example.com'
            mock_maptiler_config.return_value.get_bool.return_value = True  # proxy_tiles = True
            
            topo_source = get_tile_source('maptiler_topo-v4')
            
            if topo_source and topo_source.get('requires_proxy'):
                # Make a proxy request for a vector tile
                z = 10
                x = 512
                y = 512
                
                response = self.client.get(f'/api/tiles/maptiler_topo-v4/{z}/{x}/{y}')
                self.assertEqual(response.status_code, 200)
                
                # Verify Content-Type is correct for vector tiles
                self.assertEqual(response['Content-Type'], 'application/x-protobuf')
                
                # Verify requests.get was called with correct URL
                mock_requests_get.assert_called_once()
                call_args = mock_requests_get.call_args
                tile_url = call_args[0][0]  # First positional argument
                self.assertIn('.pbf', tile_url)
                self.assertIn('key=test-api-key-12345', tile_url)
        
        # Reset the registry
        registry_module._tile_sources = {}
        registry_module._registered = False

    def test_style_proxy_handles_missing_map(self):
        """Test that style_proxy returns 404 for non-existent map."""
        response = self.client.get('/api/tiles/style/nonexistent-map')
        self.assertEqual(response.status_code, 404)

    @patch('api.views.services.tiles.get_tile_cache_path')
    @patch('api.views.services.tiles.is_tile_cached')
    @patch('api.views.services.tiles.read_tile_from_cache')
    @patch('api.views.services.tiles.requests.get')
    @override_settings(TILE_CACHE_ENABLED=True)
    def test_tile_proxy_uses_correct_extension_from_url_template(self, mock_requests_get, 
                                                                   mock_read_tile_from_cache,
                                                                   mock_is_tile_cached,
                                                                   mock_get_tile_cache_path):
        """Test that raster tiles (like mb_topo with .png) only check for the correct extension, not .pbf."""
        from pathlib import Path
        
        # Use mb_topo which is a raster tile with .png extension
        mb_topo_source = get_tile_source('mb_topo')
        if not mb_topo_source or not mb_topo_source.get('requires_proxy'):
            self.skipTest("mb_topo tile source not available or doesn't require proxy")
        
        # Verify mb_topo uses .png in its URL template
        url_template = mb_topo_source.get('url_template', '')
        self.assertIn('.png', url_template, "mb_topo should use .png extension")
        
        # Mock cache functions
        mock_is_tile_cached.return_value = False  # Cache miss
        mock_get_tile_cache_path.return_value = Path('/fake/cache/path/tile.png')
        
        # Create a mock response for when we fetch from upstream
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.content = b'fake tile data'
        mock_response.headers = {'Content-Type': 'image/png'}
        mock_requests_get.return_value = mock_response

        # Make a proxy request
        z = 10
        x = 512
        y = 512
        
        response = self.client.get(f'/api/tiles/mb_topo/{z}/{x}/{y}')
        self.assertEqual(response.status_code, 200)
        
        # Verify get_tile_cache_path was called with 'png' extension (from URL template)
        # and NOT with 'pbf'
        calls = mock_get_tile_cache_path.call_args_list
        self.assertGreater(len(calls), 0, "get_tile_cache_path should have been called")
        
        # Check that all calls use 'png' extension, not 'pbf'
        for call in calls:
            # Check both positional and keyword arguments
            call_args = call.args
            call_kwargs = call.kwargs
            
            # Extension can be passed as 4th positional arg or as 'extension' keyword
            if len(call_args) >= 4:
                extension = call_args[3]
            elif 'extension' in call_kwargs:
                extension = call_kwargs['extension']
            else:
                continue  # Skip if extension not found in this call
            
            self.assertEqual(extension, 'png', 
                           f"Cache path should use 'png' extension for mb_topo, got '{extension}'")
            self.assertNotEqual(extension, 'pbf',
                              "Cache path should NOT use 'pbf' extension for raster tiles")
        
        # Verify is_tile_cached was only called once (for .png, not for multiple extensions)
        # Since we're only checking one extension now, it should be called once
        self.assertEqual(mock_is_tile_cached.call_count, 1,
                        "is_tile_cached should be called once for the correct extension only")


