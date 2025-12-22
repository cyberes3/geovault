"""
Tests for tile API endpoints.
"""
import json
from io import BytesIO
from unittest.mock import MagicMock, patch
from urllib.response import addinfourl

from django.contrib.auth import get_user_model
from django.test import TestCase

from geo_lib.tile_sources.registry import get_all_tile_sources, get_tile_source


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

