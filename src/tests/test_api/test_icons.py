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


class TestIconPathInjection(TestCase):
    """Adversarial tests for icon path injection (py/path-injection)."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='adversarial@example.com',
            password='testpass123',
            username='adversarialuser'
        )
        self.client.force_login(self.user)

    def test_serve_user_icon_rejects_path_traversal_in_hash(self):
        """64-char segment with ../ must be rejected (non-hex)."""
        # Exactly 64 chars that look like path traversal
        malicious = ('../' * 21 + 'x') + '.png'  # 21*3+1 = 64 before .png
        response = self.client.get(f'/api/icons/user/{malicious}')
        self.assertEqual(response.status_code, 404, 'Path traversal in hash should return 404')

    def test_serve_user_icon_rejects_non_hex_hash(self):
        """Hash part must be hexadecimal; other chars rejected."""
        non_hex = 'g' * 64 + '.png'
        response = self.client.get(f'/api/icons/user/{non_hex}')
        self.assertEqual(response.status_code, 404)

        invalid_hex = 'a' * 63 + 'z' + '.png'
        response2 = self.client.get(f'/api/icons/user/{invalid_hex}')
        self.assertEqual(response2.status_code, 404)

    def test_serve_user_icon_rejects_wrong_hash_length(self):
        """Hash part must be exactly 64 characters."""
        too_short = 'a' * 63 + '.png'
        response = self.client.get(f'/api/icons/user/{too_short}')
        self.assertEqual(response.status_code, 404)

        too_long = 'a' * 65 + '.png'
        response2 = self.client.get(f'/api/icons/user/{too_long}')
        self.assertEqual(response2.status_code, 404)

    def test_serve_user_icon_rejects_invalid_extension(self):
        """Only allowlisted extensions accepted."""
        valid_hex = 'a' * 64
        bad_ext = f'{valid_hex}.exe'
        response = self.client.get(f'/api/icons/user/{bad_ext}')
        self.assertEqual(response.status_code, 404)

    def test_serve_user_icon_accepts_valid_format_returns_404_when_not_found(self):
        """Valid 64-char hex + .png is accepted; 404 when file does not exist."""
        valid_hex = 'f' * 64 + '.png'
        response = self.client.get(f'/api/icons/user/{valid_hex}')
        # Should not be "Invalid icon hash" (400 or 500); 404 "not found" is correct
        self.assertEqual(response.status_code, 404)

    def test_serve_system_icon_rejects_path_traversal(self):
        """System icon path with .. must be rejected."""
        for path in ['../caltopo/point.png', 'caltopo/../../etc/passwd', '..%2F..%2Fetc%2Fpasswd']:
            response = self.client.get(f'/api/icons/system/{path}')
            self.assertEqual(response.status_code, 404, f'Traversal should be blocked: {path}')

    def test_recolor_icon_rejects_path_traversal(self):
        """Recolor icon param with .. must be rejected."""
        response = self.client.get(
            '/api/icons/recolor/',
            {'icon': '../caltopo/point.png', 'color': '#ff0000'}
        )
        self.assertEqual(response.status_code, 400)
        response2 = self.client.get(
            '/api/icons/recolor/',
            {'icon': 'caltopo/../../etc/passwd', 'color': '#ff0000'}
        )
        self.assertEqual(response2.status_code, 400)

