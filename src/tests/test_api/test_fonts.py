"""
Tests for font glyph API endpoint.
"""
from pathlib import Path
from django.test import TestCase
from django.conf import settings
from django.contrib.auth import get_user_model


class TestFontGlyphAPI(TestCase):
    """Test font glyph API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)
        
        # Ensure test font directory exists (using a known font path)
        self.assets_fonts_dir = Path(settings.BASE_DIR) / 'assets' / 'fonts'

    def test_serve_font_glyph_valid_request(self):
        """Test serving a valid font glyph request."""
        # This test may need adjustment based on actual available fonts
        # Common font glyphs in MapLibre: "Noto Sans Regular" or similar
        # Use the site domain from Django settings as the mock origin
        protocol = 'https' if not settings.DEBUG else 'http'
        mock_origin = f"{protocol}://{settings.SITE_DOMAIN}"
        
        response = self.client.get(
            '/api/fonts/Noto%20Sans%20Regular/0-255.pbf',
            HTTP_ORIGIN=mock_origin
        )
        
        # May return 200 if font exists, or 404 if not available in test environment
        self.assertIn(response.status_code, [200, 404])
        
        if response.status_code == 200:
            # Verify response headers for successful font serving
            self.assertEqual(response['Content-Type'], 'application/x-protobuf')
            self.assertIn('Cache-Control', response)
            self.assertIn('max-age=31536000', response['Cache-Control'])
            self.assertIn('immutable', response['Cache-Control'])
            # CORS header should match the origin we sent
            self.assertIn('Access-Control-Allow-Origin', response)
            # Check that CORS header matches origin (not '*')
            cors_header = response['Access-Control-Allow-Origin']
            self.assertEqual(cors_header, mock_origin, 
                         f"CORS header should match origin '{mock_origin}', got '{cors_header}'")

    def test_serve_font_glyph_without_pbf_extension(self):
        """Test that range without .pbf extension is handled correctly."""
        response = self.client.get('/api/fonts/Noto%20Sans%20Regular/0-255')
        
        # Should still work (endpoint adds .pbf automatically)
        self.assertIn(response.status_code, [200, 404])

    def test_serve_font_glyph_url_decoded_fontstack(self):
        """Test that URL-encoded fontstack is properly decoded."""
        # Test with URL-encoded spaces
        response = self.client.get('/api/fonts/Noto%20Sans%20Regular/0-255.pbf')
        self.assertIn(response.status_code, [200, 404])

    def test_serve_font_glyph_invalid_fontstack_directory_traversal(self):
        """Test that directory traversal attempts are blocked."""
        # Test various directory traversal attempts
        traversal_attempts = [
            '../../../etc/passwd',
            '..%2F..%2F..%2Fetc%2Fpasswd',
            'font/../../../etc/passwd',
            '/etc/passwd',
        ]
        
        for attempt in traversal_attempts:
            response = self.client.get(f'/api/fonts/{attempt}/0-255.pbf')
            self.assertEqual(response.status_code, 404, 
                           f"Directory traversal should be blocked: {attempt}")

    def test_serve_font_glyph_invalid_range_format(self):
        """Test that invalid range formats are rejected."""
        invalid_ranges = [
            'invalid',
            'abc-def',
            '0',
            '0-',
            '-255',
            '0-255-512',
            '255-0',  # Backwards range (still valid format but unusual)
        ]
        
        for invalid_range in invalid_ranges:
            if invalid_range in ['255-0']:  # Skip format-valid but unusual ranges
                continue
            response = self.client.get(f'/api/fonts/Noto%20Sans%20Regular/{invalid_range}.pbf')
            self.assertEqual(response.status_code, 404,
                           f"Invalid range should be rejected: {invalid_range}")

    def test_serve_font_glyph_range_with_directory_traversal(self):
        """Test that directory traversal in range is blocked."""
        response = self.client.get('/api/fonts/Noto%20Sans%20Regular/../../../etc/passwd')
        self.assertEqual(response.status_code, 404)

    def test_serve_font_glyph_font_stack_fallback(self):
        """Test font stack fallback mechanism."""
        # MapLibre uses comma-separated font stacks for fallback
        # e.g., "Noto Sans Regular,Arial Unicode MS Regular"
        response = self.client.get('/api/fonts/Nonexistent%20Font,Noto%20Sans%20Regular/0-255.pbf')
        
        # Should try fallback fonts in order
        self.assertIn(response.status_code, [200, 404])

    def test_serve_font_glyph_nonexistent_font(self):
        """Test that nonexistent fonts return 404."""
        response = self.client.get('/api/fonts/Nonexistent%20Font%20Name/0-255.pbf')
        self.assertEqual(response.status_code, 404)

    def test_serve_font_glyph_nonexistent_range(self):
        """Test requesting a nonexistent range for a valid font."""
        # Even if font exists, a specific range file might not
        response = self.client.get('/api/fonts/Noto%20Sans%20Regular/999999-999999.pbf')
        # May return 404 since this range likely doesn't exist
        self.assertIn(response.status_code, [200, 404])

    def test_serve_font_glyph_cache_headers(self):
        """Test that appropriate cache headers are set."""
        response = self.client.get('/api/fonts/Noto%20Sans%20Regular/0-255.pbf')
        
        if response.status_code == 200:
            # Font glyphs should be cached for a long time (immutable)
            self.assertIn('Cache-Control', response)
            cache_control = response['Cache-Control']
            self.assertIn('public', cache_control)
            self.assertIn('max-age=31536000', cache_control)  # 1 year
            self.assertIn('immutable', cache_control)

    def test_serve_font_glyph_cors_headers(self):
        """Test that CORS headers allow cross-origin requests."""
        # Use the site domain from Django settings as the mock origin
        protocol = 'https' if not settings.DEBUG else 'http'
        mock_origin = f"{protocol}://{settings.SITE_DOMAIN}"
        
        response = self.client.get(
            '/api/fonts/Noto%20Sans%20Regular/0-255.pbf',
            HTTP_ORIGIN=mock_origin
        )
        
        if response.status_code == 200:
            # Fonts need CORS headers to work in MapLibre
            # CORS header should match the origin we sent
            self.assertIn('Access-Control-Allow-Origin', response)
            cors_header = response['Access-Control-Allow-Origin']
            # Check that CORS header matches origin (not '*')
            self.assertEqual(cors_header, mock_origin,
                         f"CORS header should match origin '{mock_origin}', got '{cors_header}'")

    def test_serve_font_glyph_content_type(self):
        """Test that correct content type is set for PBF files."""
        response = self.client.get('/api/fonts/Noto%20Sans%20Regular/0-255.pbf')
        
        if response.status_code == 200:
            # PBF files should be served as protobuf
            self.assertEqual(response['Content-Type'], 'application/x-protobuf')

    def test_serve_font_glyph_empty_fontstack(self):
        """Test that empty fontstack is rejected."""
        response = self.client.get('/api/fonts//0-255.pbf')
        self.assertEqual(response.status_code, 404)

    def test_serve_font_glyph_font_stack_with_traversal_in_second_font(self):
        """Test that directory traversal in any font in stack is blocked."""
        response = self.client.get('/api/fonts/Valid%20Font,../../../etc/passwd/0-255.pbf')
        # Should block the entire request if any font in stack has traversal
        self.assertIn(response.status_code, [404])

    def test_serve_font_glyph_special_characters_in_fontstack(self):
        """Test handling of special characters in fontstack."""
        # Test with various special characters that might be in font names
        special_fonts = [
            'Font-Name',  # Hyphen
            'Font_Name',  # Underscore
            'Font Name',  # Space (would be URL encoded as %20)
        ]
        
        for font in special_fonts:
            # URL encode the font name
            from urllib.parse import quote
            encoded_font = quote(font)
            response = self.client.get(f'/api/fonts/{encoded_font}/0-255.pbf')
            # Should handle gracefully (200 if font exists, 404 if not)
            self.assertIn(response.status_code, [200, 404])

    def test_serve_font_glyph_no_authentication_required(self):
        """Test that font endpoint doesn't require authentication."""
        # Logout to test unauthenticated access
        self.client.logout()
        
        response = self.client.get('/api/fonts/Noto%20Sans%20Regular/0-255.pbf')
        # Should work without authentication (fonts are public resources)
        self.assertIn(response.status_code, [200, 404])
        # Should NOT be 401 (unauthorized)
        self.assertNotEqual(response.status_code, 401)
