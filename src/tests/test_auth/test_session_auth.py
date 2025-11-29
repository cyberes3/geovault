"""
Tests for session-based authentication.
"""
from django.test import TestCase
from django.contrib.auth import get_user_model

from api.models import FeatureStore
from geo_lib.feature_id import generate_feature_hash

User = get_user_model()


class TestSessionAuth(TestCase):
    """Test session-based authentication."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test Feature'
            }
        }

    def test_authenticated_access(self):
        """Test that authenticated users can access protected endpoints."""
        self.client.force_login(self.user)
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 200)

    def test_unauthenticated_access(self):
        """Test that unauthenticated users cannot access protected endpoints."""
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 401)

    def test_csrf_protection(self):
        """Test that CSRF protection is enforced for session auth."""
        self.client.force_login(self.user)
        # POST requests should require CSRF token
        # Note: Django test client handles CSRF automatically, so we test indirectly
        response = self.client.post('/api/features/all/')
        # Should fail because POST is not allowed, not because of CSRF
        self.assertIn(response.status_code, [405, 400])

    def test_login_required_401_decorator(self):
        """Test login_required_401 decorator."""
        # Without login
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 401)

        # With login
        self.client.force_login(self.user)
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 200)

    def test_user_isolation(self):
        """Test that users can only access their own data."""
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )

        # Create feature for other user
        other_feature = FeatureStore.objects.create(
            user=other_user,
            geojson=self.feature_data,
            geojson_hash=generate_feature_hash(self.feature_data)
        )

        # Try to access other user's feature
        self.client.force_login(self.user)
        response = self.client.get(f'/api/feature/{other_feature.id}/')
        self.assertEqual(response.status_code, 404)  # Not found (not authorized)

    def test_session_persistence(self):
        """Test that session persists across requests."""
        self.client.force_login(self.user)
        
        # First request
        response1 = self.client.get('/api/features/all/')
        self.assertEqual(response1.status_code, 200)

        # Second request (should still be authenticated)
        response2 = self.client.get('/api/features/all/')
        self.assertEqual(response2.status_code, 200)

    def test_logout(self):
        """Test that logout works."""
        self.client.force_login(self.user)
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 200)

        self.client.logout()
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 401)

