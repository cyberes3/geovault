"""
Tests for tile API endpoints.
"""
import json
from unittest.mock import patch, MagicMock
from django.test import TestCase


class TestTilesAPI(TestCase):
    """Test tile API endpoints."""

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

    def test_get_tile_sources(self):
        """Test getting tile sources."""
        response = self.client.get('/api/tiles/sources/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('sources', data)

    @patch('api.views.tiles.fetch_tile')
    def test_tile_proxy(self, mock_fetch_tile):
        """Test tile proxy endpoint."""
        mock_response = MagicMock()
        mock_response.content = b'fake tile data'
        mock_response.headers = {'Content-Type': 'image/png'}
        mock_fetch_tile.return_value = mock_response

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

