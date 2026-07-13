"""CORS and Content-Security-Policy headers, plus the tile-response cookie-stripping fallback."""
import logging
from urllib.parse import urlparse

from geo_lib.logging.console import get_tagged_logger
from geo_lib.tile_sources.registry import get_all_tile_sources
from website.middleware.tile_cookies import is_tile_request, strip_response_cookies
from website.public_url import public_base_url
from website.settings_utils import get_setting

_logger = get_tagged_logger()


def get_tile_source_origins():
    """
    Extract origins from external tile sources that don't require proxy.
    These origins will be used for CORS and CSP configuration.

    Returns:
        list: List of origin URLs (e.g., ['https://tile.opentopomap.org'])
    """

    origins = set()

    try:
        tile_sources = get_all_tile_sources()
        for source_config in tile_sources.values():
            # Only include external tile sources that don't require proxy
            if not source_config.get('requires_proxy', False):
                # Try to extract origin from url_template
                url_template = source_config.get('url_template')
                if url_template:
                    try:
                        # Replace common template variables before parsing
                        clean_url = url_template.replace('{s}', 'a').replace('{z}', '0').replace('{x}', '0').replace('{y}', '0')
                        parsed = urlparse(clean_url)
                        if parsed.scheme and parsed.netloc:
                            # Extract the base netloc without subdomain placeholders
                            netloc = parsed.netloc
                            # If the original had {s} in netloc, we need to handle wildcard
                            if '{s}' in url_template:
                                # Remove the placeholder subdomain part and use wildcard
                                # For a.tile.opentopomap.org, we want tile.opentopomap.org
                                parts = netloc.split('.')
                                if len(parts) > 2:
                                    # Use the last two parts (domain.tld) or last three for co.uk style
                                    if len(parts) >= 3 and parts[-2] in ['co', 'org', 'com', 'net']:
                                        netloc = '.'.join(parts[-3:])
                                    else:
                                        netloc = '.'.join(parts[-2:])
                            origin = f"{parsed.scheme}://{netloc}"
                            origins.add(origin)
                    except Exception:
                        pass

                # Also check client_config.url
                client_config = source_config.get('client_config', {})
                client_url = client_config.get('url')
                if client_url and not client_url.startswith('/'):
                    try:
                        # Replace common template variables before parsing
                        clean_url = client_url.replace('{s}', 'a').replace('{z}', '0').replace('{x}', '0').replace('{y}', '0')
                        parsed = urlparse(clean_url)
                        if parsed.scheme and parsed.netloc:
                            netloc = parsed.netloc
                            # Handle subdomain placeholders
                            if '{s}' in client_url:
                                parts = netloc.split('.')
                                if len(parts) > 2:
                                    if len(parts) >= 3 and parts[-2] in ['co', 'org', 'com', 'net']:
                                        netloc = '.'.join(parts[-3:])
                                    else:
                                        netloc = '.'.join(parts[-2:])
                            origin = f"{parsed.scheme}://{netloc}"
                            origins.add(origin)
                    except Exception:
                        pass
    except Exception as e:
        # If tile sources aren't loaded yet (during startup), return empty list
        # The middleware will call this again later when needed
        logger = logging.getLogger('config')
        logger.debug(f"Could not load tile source origins during settings initialization: {e}")

    return list(origins)


class CustomHeaderMiddleware:
    """Middleware to add security headers including CORS and CSP."""

    def __init__(self, get_response):
        self.get_response = get_response
        self._cors_origins = None
        self._csp_connect_src = None

    def _get_cors_origins(self):
        """Get list of allowed CORS origins (cached)."""
        if self._cors_origins is None:

            origins = {public_base_url()}

            # Add external tile source origins
            try:
                tile_origins = get_tile_source_origins()
                origins.update(tile_origins)
            except Exception as e:
                # Log but don't fail if tile sources aren't available yet
                _logger.debug(f"Could not load tile source origins: {e}")

            # Add user-configured additional origins
            additional_origins = get_setting('ADDITIONAL_CORS_ORIGINS', [])
            origins.update(additional_origins)

            self._cors_origins = list(origins)

        return self._cors_origins

    def _get_csp_connect_src(self):
        """Get CSP connect-src directive (cached)."""
        if self._csp_connect_src is None:

            # Start with 'self'
            connect_sources = ["'self'"]

            # Add external tile source origins for connect-src
            try:
                tile_origins = get_tile_source_origins()
                connect_sources.extend(tile_origins)
            except Exception:
                pass

            self._csp_connect_src = ' '.join(connect_sources)

        return self._csp_connect_src

    def _set_cors_headers(self, request, response):
        """Set CORS headers on response."""
        # Explicitly remove any existing Access-Control-Allow-Origin header
        # to ensure we override any value set by WhiteNoise or other middleware
        response.headers.pop('Access-Control-Allow-Origin', None)

        # Get the Origin header from the request
        origin = request.META.get('HTTP_ORIGIN')

        allowed_origins = self._get_cors_origins()

        # Check if the origin is allowed
        if origin and origin in allowed_origins:
            response['Access-Control-Allow-Origin'] = origin
            response['Access-Control-Allow-Credentials'] = 'true'
        else:
            # For requests without Origin header or from same origin, allow the site domain
            response['Access-Control-Allow-Origin'] = public_base_url()

        # Set other CORS headers for preflight requests
        if request.method == 'OPTIONS':
            response['Access-Control-Allow-Methods'] = 'GET, POST, PUT, DELETE, PATCH, OPTIONS'
            response['Access-Control-Allow-Headers'] = 'Content-Type, Authorization, X-Requested-With, Accept'
            response['Access-Control-Max-Age'] = '86400'  # 24 hours

    def _set_csp_headers(self, response):
        """Set Content Security Policy headers on response."""
        # Build CSP directives
        csp_directives = [
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline'",
            "worker-src 'self' blob:",  # Allow blob: workers for MapLibre GL JS
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob: https:",
            f"connect-src {self._get_csp_connect_src()}",
            "font-src 'self' data:",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'"
        ]

        csp_policy = '; '.join(csp_directives)
        response['Content-Security-Policy'] = csp_policy

    def __call__(self, request):
        # Track if this is a tile request BEFORE getting the response
        # This allows us to prevent session saving for tile requests
        tile_request = is_tile_request(request)

        # Prevent session from being saved for tile requests
        # This stops the session middleware from setting Set-Cookie headers
        if tile_request and hasattr(request, 'session'):
            # Mark session as not modified to prevent saving
            request.session.modified = False

        response = self.get_response(request)

        # Remove Set-Cookie headers from tile proxy responses (fallback safety measure)
        # session.CustomSessionMiddleware also strips its own narrower set of cookies further
        # out in the response chain; we do the broad version here too since this runs closer to
        # the view and Cloudflare does not cache responses with any Set-Cookie header.
        if tile_request:
            strip_response_cookies(response)

        # Set CORS headers on all responses
        self._set_cors_headers(request, response)

        # For tile requests, ensure Access-Control-Allow-Credentials is not set
        # to maximize cacheability (CORS spec also requires credentials=false when
        # Access-Control-Allow-Origin is *)
        if tile_request and response.has_header('Access-Control-Allow-Credentials'):
            del response['Access-Control-Allow-Credentials']

        # Set CSP headers on HTML responses
        content_type = response.get('Content-Type', '')
        if 'text/html' in content_type:
            self._set_csp_headers(response)

        return response
