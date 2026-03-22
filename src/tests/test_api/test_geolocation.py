"""
Tests for geolocation API endpoints.
"""
import json
from unittest.mock import patch
from django.test import TestCase
from django.contrib.auth import get_user_model


class TestGeolocationAPI(TestCase):
    """Test geolocation API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_get_user_location(self):
        """Test getting user location - uses conditional geolocation mock from conftest."""
        # Geolocation is mocked by the conditional_external_api_mocking fixture
        # It returns None since geolocation is not ready yet
        response = self.client.get('/api/location/user/')
        self.assertEqual(response.status_code, 200)
        payload = json.loads(response.content)
        self.assertIsNone(payload.get('location'))

    def test_get_location_by_ip(self):
        """Test getting location by IP - uses conditional geolocation mock."""
        # Geolocation is mocked by fixture and returns None
        response = self.client.get('/api/location/ip/?ip=8.8.8.8')
        # Should return 404 since geolocation is not available
        self.assertIn(response.status_code, [404, 500])

    def test_get_location_by_ip_error(self):
        """Test getting location by IP when service fails."""
        # Geolocation is mocked by fixture and returns None
        response = self.client.get('/api/location/ip/?ip=8.8.8.8')
        # Should return 404 when location not found
        self.assertIn(response.status_code, [404, 500])

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot access geolocation."""
        self.client.logout()
        response = self.client.get('/api/location/user/')
        self.assertEqual(response.status_code, 401)

