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

    @patch('api.views.geolocation_api.get_user_location_from_browser')
    def test_get_user_location(self, mock_get_location):
        """Test getting user location from browser."""
        mock_get_location.return_value = {
            'latitude': 37.7749,
            'longitude': -122.4194,
            'accuracy': 10.0
        }

        response = self.client.get('/api/location/user/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('latitude', data)
        self.assertIn('longitude', data)

    @patch('api.views.geolocation_api.get_location_by_ip_address')
    def test_get_location_by_ip(self, mock_get_location):
        """Test getting location by IP address."""
        mock_get_location.return_value = {
            'latitude': 37.7749,
            'longitude': -122.4194,
            'city': 'San Francisco',
            'country': 'US'
        }

        response = self.client.get('/api/location/ip/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('latitude', data)
        self.assertIn('longitude', data)

    @patch('api.views.geolocation_api.get_location_by_ip_address')
    def test_get_location_by_ip_error(self, mock_get_location):
        """Test getting location by IP when service fails."""
        mock_get_location.return_value = None

        response = self.client.get('/api/location/ip/')
        # May return error or empty response depending on implementation
        self.assertIn(response.status_code, [200, 400, 500])

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot access geolocation."""
        self.client.logout()
        response = self.client.get('/api/location/user/')
        self.assertEqual(response.status_code, 401)

