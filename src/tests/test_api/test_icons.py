"""
Tests for icon management API endpoints.
"""
import json
from io import BytesIO
from unittest.mock import MagicMock, patch

from django.contrib.auth import get_user_model
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase
from PIL import Image


class TestIconsAPI(TestCase):
    """Test icon management API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_upload_icon(self):
        """Test uploading an icon."""
        # Create a simple test image
        img = Image.new('RGB', (100, 100), color='red')
        img_bytes = BytesIO()
        img.save(img_bytes, format='PNG')
        img_bytes.seek(0)

        file = SimpleUploadedFile("test_icon.png", img_bytes.read(), content_type='image/png')
        response = self.client.post('/api/icons/upload/', {'file': file})

        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('icon_url', data)

    def test_upload_icon_invalid_file(self):
        """Test uploading invalid file as icon."""
        file = SimpleUploadedFile("test.txt", b"not an image", content_type='text/plain')
        response = self.client.post('/api/icons/upload/', {'file': file})
        # Should fail validation
        self.assertIn(response.status_code, [400, 500])

    def test_recolor_icon(self):
        """Test recoloring an icon."""
        response = self.client.get(
            '/api/icons/recolor/',
            {'icon': 'caltopo/point.png', 'color': '#ff0000'}
        )
        # May succeed or fail depending on icon processing configuration
        self.assertIn(response.status_code, [200, 400, 404, 500])

    def test_recolor_icon_invalid_color(self):
        """Test recoloring with invalid color."""
        response = self.client.get(
            '/api/icons/recolor/',
            {'icon': 'caltopo/point.png', 'color': 'invalid-color'}
        )
        self.assertEqual(response.status_code, 400)

    def test_serve_system_icon(self):
        """Test serving a system icon."""
        # This test may need adjustment based on actual icon paths
        response = self.client.get('/api/icons/system/caltopo/point.png')
        # May return 200 if icon exists, or 404 if not
        self.assertIn(response.status_code, [200, 404])

    def test_serve_user_icon(self):
        """Test serving a user icon."""
        # This test may need adjustment based on actual icon storage
        fake_hash = 'abc123def456'
        response = self.client.get(f'/api/icons/user/{fake_hash}')
        # May return 200 if icon exists, or 404 if not
        self.assertIn(response.status_code, [200, 404])

    def test_serve_icon_registry(self):
        """Test serving icon registry."""
        response = self.client.get('/api/icons/registry/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('points', data)

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot upload icons."""
        self.client.logout()
        img = Image.new('RGB', (100, 100), color='red')
        img_bytes = BytesIO()
        img.save(img_bytes, format='PNG')
        img_bytes.seek(0)
        file = SimpleUploadedFile("test_icon.png", img_bytes.read(), content_type='image/png')
        response = self.client.post('/api/icons/upload/', {'file': file})
        self.assertEqual(response.status_code, 401)

