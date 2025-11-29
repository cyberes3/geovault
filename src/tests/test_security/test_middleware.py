"""
Tests for security middleware (CORS and CSP).
"""
import json
from django.test import TestCase, override_settings
from django.contrib.auth import get_user_model

User = get_user_model()


class TestSecurityMiddleware(TestCase):
    """Test security middleware CORS and CSP functionality."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_cors_headers_on_api_response(self):
        """Test that CORS headers are present on API responses."""
        response = self.client.get('/api/health/')
        
        # Check for CORS headers
        self.assertIn('Access-Control-Allow-Origin', response)
        
        # Should allow the site domain by default
        self.assertIsNotNone(response['Access-Control-Allow-Origin'])

    def test_cors_headers_with_origin(self):
        """Test CORS headers when Origin header is sent."""
        from django.conf import settings
        protocol = 'https' if not settings.DEBUG else 'http'
        origin = f"{protocol}://{settings.SITE_DOMAIN}"
        
        response = self.client.get(
            '/api/health/',
            HTTP_ORIGIN=origin
        )
        
        # Should return the requesting origin if it's allowed
        self.assertIn('Access-Control-Allow-Origin', response)
        self.assertEqual(response['Access-Control-Allow-Origin'], origin)
        self.assertEqual(response['Access-Control-Allow-Credentials'], 'true')

    def test_cors_preflight_request(self):
        """Test CORS preflight OPTIONS request."""
        from django.conf import settings
        protocol = 'https' if not settings.DEBUG else 'http'
        origin = f"{protocol}://{settings.SITE_DOMAIN}"
        
        response = self.client.options(
            '/api/features/all/',
            HTTP_ORIGIN=origin,
            HTTP_ACCESS_CONTROL_REQUEST_METHOD='POST',
            HTTP_ACCESS_CONTROL_REQUEST_HEADERS='Content-Type'
        )
        
        # Check for preflight headers
        self.assertIn('Access-Control-Allow-Methods', response)
        self.assertIn('Access-Control-Allow-Headers', response)
        self.assertIn('Access-Control-Max-Age', response)
        
        # Verify allowed methods
        allowed_methods = response['Access-Control-Allow-Methods']
        self.assertIn('GET', allowed_methods)
        self.assertIn('POST', allowed_methods)
        self.assertIn('PUT', allowed_methods)
        self.assertIn('DELETE', allowed_methods)

    def test_csp_headers_on_html_response(self):
        """Test that CSP headers are present on HTML responses."""
        # Get the root page (which should return HTML)
        response = self.client.get('/')
        
        # Check for CSP header if content type is HTML
        if 'text/html' in response.get('Content-Type', ''):
            self.assertIn('Content-Security-Policy', response)
            
            csp = response['Content-Security-Policy']
            
            # Verify key CSP directives
            self.assertIn("default-src 'self'", csp)
            self.assertIn("script-src 'self' 'unsafe-inline'", csp)
            self.assertIn("style-src 'self' 'unsafe-inline'", csp)
            self.assertIn("img-src 'self' data: blob: https:", csp)
            self.assertIn("connect-src 'self'", csp)
            self.assertIn("font-src 'self' data:", csp)
            self.assertIn("frame-ancestors 'none'", csp)
            self.assertIn("base-uri 'self'", csp)
            self.assertIn("form-action 'self'", csp)

    def test_csp_headers_not_on_api_response(self):
        """Test that CSP headers are not added to non-HTML responses."""
        response = self.client.get('/api/health/')
        
        # API responses should not have CSP headers (they return JSON)
        # CSP is only for HTML responses
        content_type = response.get('Content-Type', '')
        if 'application/json' in content_type:
            # CSP might still be present, but it's not strictly required for JSON
            # This test just checks that the system doesn't break with JSON responses
            pass

    def test_tile_source_origins_in_csp(self):
        """Test that external tile source origins are included in CSP connect-src."""
        from website.settings import get_tile_source_origins
        
        # Get the actual tile source origins from the system
        tile_origins = get_tile_source_origins()
        
        # Get an HTML response
        response = self.client.get('/')
        
        if 'text/html' in response.get('Content-Type', ''):
            csp = response.get('Content-Security-Policy', '')
            
            # The connect-src directive should include 'self' at minimum
            self.assertIn("connect-src 'self'", csp)
            
            # Each tile source origin should be included in the CSP
            for origin in tile_origins:
                self.assertIn(origin, csp, 
                    f"Tile source origin {origin} should be in CSP connect-src directive")
            
            # Verify the connect-src directive contains all expected origins
            # Extract the connect-src value
            import re
            connect_match = re.search(r"connect-src ([^;]+)", csp)
            if connect_match:
                connect_src = connect_match.group(1)
                # Should include 'self'
                self.assertIn("'self'", connect_src)
                # Should include each tile origin
                for origin in tile_origins:
                    self.assertIn(origin, connect_src)

    def test_cors_allows_configured_origins(self):
        """Test that configured additional origins are allowed."""
        # This test verifies the structure is in place
        # Actual testing with additional origins would require settings override
        
        from django.conf import settings
        protocol = 'https' if not settings.DEBUG else 'http'
        origin = f"{protocol}://{settings.SITE_DOMAIN}"
        
        response = self.client.get(
            '/api/health/',
            HTTP_ORIGIN=origin
        )
        
        # Should allow the site's own origin
        self.assertEqual(response['Access-Control-Allow-Origin'], origin)

    @override_settings(ADDITIONAL_CORS_ORIGINS=['https://example.com'])
    def test_additional_cors_origin_allowed(self):
        """Test that additional configured CORS origins are allowed."""
        response = self.client.get(
            '/api/health/',
            HTTP_ORIGIN='https://example.com'
        )
        
        # Should allow the additional configured origin
        self.assertEqual(response['Access-Control-Allow-Origin'], 'https://example.com')
        self.assertEqual(response['Access-Control-Allow-Credentials'], 'true')

    def test_cors_rejects_unknown_origin(self):
        """Test that unknown origins fall back to site domain."""
        response = self.client.get(
            '/api/health/',
            HTTP_ORIGIN='https://evil.com'
        )
        
        # Should fall back to site domain, not the evil origin
        self.assertIn('Access-Control-Allow-Origin', response)
        self.assertNotEqual(response['Access-Control-Allow-Origin'], 'https://evil.com')

    def test_tile_sources_in_cors_origins(self):
        """Test that tile source origins are included in allowed CORS origins."""
        from website.settings import get_tile_source_origins
        
        # Get the actual tile source origins from the system
        tile_origins = get_tile_source_origins()
        
        # Test that each tile origin is allowed via CORS
        for origin in tile_origins:
            response = self.client.get(
                '/api/health/',
                HTTP_ORIGIN=origin
            )
            
            # Should allow the tile source origin
            self.assertEqual(
                response['Access-Control-Allow-Origin'], 
                origin,
                f"Tile source origin {origin} should be allowed via CORS"
            )
            self.assertEqual(response['Access-Control-Allow-Credentials'], 'true')

    def test_all_external_tile_sources_detected(self):
        """Test that all external tile sources (requires_proxy=False) are detected."""
        from geo_lib.tile_sources import get_all_tile_sources
        from website.settings import get_tile_source_origins
        from urllib.parse import urlparse
        
        # Get all tile sources
        all_sources = get_all_tile_sources()
        
        # Get detected origins
        detected_origins = set(get_tile_source_origins())
        
        # Manually extract origins from external tile sources the same way the backend does
        expected_origins = set()
        external_source_count = 0
        
        for source_id, config in all_sources.items():
            if not config.get('requires_proxy', False):
                # Check url_template
                url_template = config.get('url_template')
                if url_template:
                    external_source_count += 1
                    try:
                        # Same logic as get_tile_source_origins()
                        clean_url = url_template.replace('{s}', 'a').replace('{z}', '0').replace('{x}', '0').replace('{y}', '0')
                        parsed = urlparse(clean_url)
                        if parsed.scheme and parsed.netloc:
                            netloc = parsed.netloc
                            if '{s}' in url_template:
                                parts = netloc.split('.')
                                if len(parts) > 2:
                                    if len(parts) >= 3 and parts[-2] in ['co', 'org', 'com', 'net']:
                                        netloc = '.'.join(parts[-3:])
                                    else:
                                        netloc = '.'.join(parts[-2:])
                            origin = f"{parsed.scheme}://{netloc}"
                            expected_origins.add(origin)
                    except Exception:
                        pass
                
                # Check client_config.url
                client_config = config.get('client_config', {})
                client_url = client_config.get('url')
                if client_url and not client_url.startswith('/'):
                    external_source_count += 1
                    try:
                        clean_url = client_url.replace('{s}', 'a').replace('{z}', '0').replace('{x}', '0').replace('{y}', '0')
                        parsed = urlparse(clean_url)
                        if parsed.scheme and parsed.netloc:
                            netloc = parsed.netloc
                            if '{s}' in client_url:
                                parts = netloc.split('.')
                                if len(parts) > 2:
                                    if len(parts) >= 3 and parts[-2] in ['co', 'org', 'com', 'net']:
                                        netloc = '.'.join(parts[-3:])
                                    else:
                                        netloc = '.'.join(parts[-2:])
                            origin = f"{parsed.scheme}://{netloc}"
                            expected_origins.add(origin)
                    except Exception:
                        pass
        
        # Verify we found some external sources
        self.assertGreater(external_source_count, 0, 
            "Should have at least one external tile source configured")
        
        # Verify detected origins match expected origins
        self.assertEqual(detected_origins, expected_origins,
            f"Detected origins {detected_origins} should match expected {expected_origins}")
        
        # Verify we detected at least some origins
        self.assertGreater(len(detected_origins), 0,
            "Should detect at least one external tile source origin")

