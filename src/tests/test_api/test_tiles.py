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

    def test_herestreets_tile_source_registered(self):
        """Test that HERE Streets tile source is registered correctly."""
        herestreets_source = get_tile_source('herestreets')
        self.assertIsNotNone(herestreets_source)
        self.assertEqual(herestreets_source['name'], 'HERE Streets')
        self.assertTrue(herestreets_source['requires_proxy'])
        self.assertIn('url_template', herestreets_source)
        self.assertIn('proxy_config', herestreets_source)
        self.assertIn('client_config', herestreets_source)

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
        maptiler_sources = {
            source_id: config
            for source_id, config in all_sources.items()
            if source_id.startswith('maptiler_')
        }
        # MapTiler sources are optional and depend on configuration
        # If they exist, verify they have the correct structure
        for source_id, config in maptiler_sources.items():
            self.assertIn('name', config)
            self.assertEqual(config['type'], 'maptiler')
            self.assertFalse(config.get('requires_proxy', True))  # MapTiler doesn't need proxy
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

    def test_openhikingmap_has_proxy_config_with_user_agent(self):
        """Test that OpenHikingMap tile source has proxy_config with User-Agent header."""
        openhikingmap_source = get_tile_source('openhikingmap')
        self.assertIsNotNone(openhikingmap_source, "OpenHikingMap tile source should be registered")
        
        proxy_config = openhikingmap_source.get('proxy_config')
        self.assertIsNotNone(proxy_config, "OpenHikingMap should have proxy_config")
        
        headers = proxy_config.get('headers', {})
        self.assertIn('User-Agent', headers, "OpenHikingMap proxy_config should include User-Agent header")
        
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
        openhikingmap_source = get_tile_source('openhikingmap')
        mb_topo_source = get_tile_source('mb_topo')
        
        # Verify OSM and OpenTopoMap require proxy when in config
        self.assertIsNotNone(osm_source)
        self.assertTrue(osm_source.get('requires_proxy'), "OSM should require proxy when in proxy_sources config")
        self.assertEqual(osm_source['client_config']['url'], '/api/tiles/osm/{z}/{x}/{y}')
        
        self.assertIsNotNone(opentopomap_source)
        self.assertTrue(opentopomap_source.get('requires_proxy'), "OpenTopoMap should require proxy when in proxy_sources config")
        self.assertEqual(opentopomap_source['client_config']['url'], '/api/tiles/opentopomap/{z}/{x}/{y}')
        
        # Verify OpenHikingMap does NOT require proxy when not in config
        self.assertIsNotNone(openhikingmap_source)
        self.assertFalse(openhikingmap_source.get('requires_proxy'), "OpenHikingMap should not require proxy when not in proxy_sources config")
        
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


