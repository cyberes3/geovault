"""
Tests for geolocation API endpoints.
"""
import json
from unittest.mock import patch
from django.test import TestCase


class TestGeolocationAPI(TestCase):
    """Test geolocation API endpoints."""

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

    @patch('geo_lib.geolocation.ip_service.get_geolocation_service')
    def test_get_user_location(self, mock_get_service):
        """Test getting user location from browser."""
        mock_service = mock_get_service.return_value
        mock_service.get_client_ip.return_value = '127.0.0.1'
        mock_service.get_location_from_ip.return_value = {
            'latitude': 37.7749,
            'longitude': -122.4194,
            'city': 'San Francisco',
            'state': 'California',
            'country': 'US',
            'timezone': 'America/Los_Angeles'
        }
        mock_service.reader = True  # Indicate database is available

        response = self.client.get('/api/location/user/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('location', data)
        self.assertIn('latitude', data['location'])
        self.assertIn('longitude', data['location'])

    @patch('api.views.geolocation_api.get_geolocation_service')
    def test_get_location_by_ip(self, mock_get_service):
        """Test getting location by IP address."""
        from unittest.mock import MagicMock
        mock_service = MagicMock()
        mock_get_service.return_value = mock_service
        mock_service.get_client_ip.return_value = '8.8.8.8'
        mock_service.get_location_from_ip.return_value = {
            'latitude': 37.7749,
            'longitude': -122.4194,
            'city': 'San Francisco',
            'state': 'California',
            'country': 'US',
            'timezone': 'America/Los_Angeles',
            'ip': '8.8.8.8'
        }

        response = self.client.get('/api/location/ip/?ip=8.8.8.8')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('location', data)
        self.assertIn('latitude', data['location'])
        self.assertIn('longitude', data['location'])

    @patch('geo_lib.geolocation.ip_service.get_geolocation_service')
    def test_get_location_by_ip_error(self, mock_get_service):
        """Test getting location by IP when service fails."""
        mock_service = mock_get_service.return_value
        mock_service.get_client_ip.return_value = '8.8.8.8'
        mock_service.get_location_from_ip.return_value = None

        response = self.client.get('/api/location/ip/?ip=8.8.8.8')
        # Should return 404 when location not found
        self.assertEqual(response.status_code, 404)

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot access geolocation."""
        self.client.logout()
        response = self.client.get('/api/location/user/')
        self.assertEqual(response.status_code, 401)

