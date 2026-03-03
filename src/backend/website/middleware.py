import hashlib
import logging
import time
import traceback
from urllib.parse import urlparse

from django.contrib.auth.models import AnonymousUser
from django.contrib.sessions.middleware import SessionMiddleware

from geo_lib.logging.console import get_tagged_logger
from geo_lib.tile_sources.registry import get_all_tile_sources
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier
from users.api_keys import validate_api_key
from users.models import UserProfile
from website.settings_utils import get_required_setting, get_setting

_logger = get_tagged_logger()

# In-memory cache for activity tracking throttling
# Format: {user_id: last_update_timestamp}
_activity_tracking_cache = {}
# Throttle activity updates to at most once per 30 seconds per user
ACTIVITY_TRACKING_THROTTLE_SECONDS = 30


class CustomSessionMiddleware(SessionMiddleware):
    """
    Custom SessionMiddleware that prevents session cookies for tile requests.
    Based on: https://stackoverflow.com/questions/62486176/how-to-disable-cookies-in-django-manually
    """
    
    def process_response(self, request, response):
        # Call parent to handle normal session processing
        response = super().process_response(request, response)
        
        # Remove session cookie for tile requests to allow Cloudflare caching
        if request.path.startswith('/api/tiles/'):
            # Delete the session cookie from response.cookies
            # This prevents Set-Cookie header from being set
            session_cookie = get_setting('SESSION_COOKIE_NAME', 'sessionid')
            if session_cookie in response.cookies:
                del response.cookies[session_cookie]
            
            # Also remove CSRF cookie if present
            csrf_cookie = get_setting('CSRF_COOKIE_NAME', 'csrftoken')
            if csrf_cookie in response.cookies:
                del response.cookies[csrf_cookie]
            
            # Remove Vary: Cookie header which also prevents caching
            # Django's SessionMiddleware sets this when cookies are present
            if response.has_header('Vary'):
                vary_value = response['Vary']
                # Remove 'Cookie' from Vary header if present (case-insensitive)
                vary_parts = [v.strip() for v in vary_value.split(',')]
                vary_parts = [v for v in vary_parts if v.lower() != 'cookie']
                if vary_parts:
                    response['Vary'] = ', '.join(vary_parts)
                else:
                    # If Vary becomes empty, remove it entirely
                    del response['Vary']
        
        return response


def _get_content_length(response):
    """Extract content length from response."""
    try:
        if hasattr(response, 'get'):
            return response.get('Content-Length', '')
        if hasattr(response, 'headers'):
            return response.headers.get('Content-Length', '')
        if hasattr(response, '_headers'):
            header_val = response._headers.get('content-length', ('', ''))
            return header_val[1] if isinstance(header_val, tuple) else header_val
    except:
        pass
    return ''


class LoggingMiddleware:
    """Middleware to log all HTTP requests and catch unhandled exceptions."""

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        client_ip = get_client_ip(request)

        try:
            response = self.get_response(request)
            # Get user identifier AFTER authentication middleware has run
            user_identifier = get_user_identifier(request)
        except Exception as e:
            # Log just the traceback
            traceback_str = traceback.format_exc()
            _logger.error(traceback_str)

            # Re-raise the exception so Django's exception handling can process it
            # This ensures got_request_exception signal fires and handler500 is called
            # Response formatting is handled by custom_exception_handler in exception_handler.py
            raise

        # Log API requests and errors
        if request.path.startswith('/api/'):
            query_string = request.GET.urlencode()
            api_key_suffix = ' (API KEY)' if getattr(request, 'is_api_authenticated', False) else ''
            if query_string:
                log_msg = f"{request.method} {request.path}?{query_string} - {user_identifier}{api_key_suffix} - {client_ip}"
            else:
                log_msg = f"{request.method} {request.path} - {user_identifier}{api_key_suffix} - {client_ip}"

            if response.status_code >= 400:
                # Log errors with status
                _logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                # Log successful requests
                _logger.info(log_msg)

        # Log static file requests (no username for static files)
        elif request.path.startswith('/static/'):
            # Get file size if available
            content_length = _get_content_length(response)

            # Build log message
            if content_length:
                log_msg = f"{request.method} {request.path} - {client_ip} - {content_length} bytes"
            else:
                log_msg = f"{request.method} {request.path} - {client_ip}"

            # Log based on status code
            if response.status_code >= 400:
                _logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                _logger.info(log_msg)

        # Log favicon requests (no username)
        elif request.path == '/favicon.ico':
            # Get file size if available
            content_length = _get_content_length(response)

            if content_length:
                log_msg = f"{request.method} {request.path} - {client_ip} - {content_length} bytes"
            else:
                log_msg = f"{request.method} {request.path} - {client_ip}"

            if response.status_code >= 400:
                _logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                _logger.info(log_msg)

        # Log root and other non-API requests (no username)
        elif not request.path.startswith('/api/') and not request.path.startswith('/admin/') and not request.path.startswith('/account/'):
            # This catches root path and other non-API routes
            # Skip static files as they're handled above, but this is a fallback
            if not request.path.startswith('/static/'):
                log_msg = f"{request.method} {request.path} - {client_ip}"
                if response.status_code >= 400:
                    _logger.warning(f"{log_msg} - Status: {response.status_code}")
                else:
                    _logger.info(log_msg)

        return response


def _resolve_oauth2_access_token(token_string):
    """
    Resolve Bearer token as an OAuth2 access token. Returns (user, access_token) if valid,
    else None. Caller must ensure token_string is non-empty.
    """
    from oauth2_provider.models import get_access_token_model

    if not token_string:
        return None
    token_checksum = hashlib.sha256(token_string.encode("utf-8")).hexdigest()
    AccessToken = get_access_token_model()
    try:
        token = AccessToken.objects.select_related("user").get(token_checksum=token_checksum)
    except AccessToken.DoesNotExist:
        return None
    if token.is_expired():
        return None
    return (token.user, token)


class APIKeyResolutionMiddleware:
    """
    Resolve Bearer token for /api/ requests when user is not yet authenticated.
    Tries OAuth2 access token first, then API key. Sets request.user,
    request.is_api_authenticated, and optionally request.api_key or request.oauth2_access_token.
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if request.path.startswith('/api/'):
            auth_header = request.META.get('HTTP_AUTHORIZATION', '')
            if auth_header.startswith('Bearer ') and not request.user.is_authenticated:
                token = auth_header[7:].strip()
                if token:
                    oauth_result = _resolve_oauth2_access_token(token)
                    if oauth_result is not None:
                        user, access_token = oauth_result
                        request.user = user
                        request.oauth2_access_token = access_token
                        request.is_api_authenticated = True
                        # Track last use for settings UI (touch updated)
                        access_token.save(update_fields=["updated"])
                    else:
                        result = validate_api_key(token)
                        if result is not None:
                            user, api_key = result
                            request.user = user
                            request.api_key = api_key
                            request.is_api_authenticated = True
        return self.get_response(request)


class ActivityTrackingMiddleware:
    """Middleware to track user activity by updating last_activity timestamp."""

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        # Process request first
        response = self.get_response(request)

        # Update activity for authenticated users
        # Skip static files, icons, fonts, and other non-user-interactive paths
        # Icon and font requests are static assets and shouldn't trigger activity tracking
        if (request.user and
                not isinstance(request.user, AnonymousUser) and
                not request.path.startswith('/static/') and
                not request.path.startswith('/api/icons/') and
                not request.path.startswith('/api/fonts/') and
                request.path != '/favicon.ico'):
            try:
                # Throttle activity updates to reduce database load
                # Only update if enough time has passed since last update for this user
                user_id = request.user.id
                current_time = time.time()
                last_update = _activity_tracking_cache.get(user_id, 0)

                if current_time - last_update >= ACTIVITY_TRACKING_THROTTLE_SECONDS:
                    profile, _ = UserProfile.get_or_create_profile(request.user)
                    profile.update_activity()
                    _activity_tracking_cache[user_id] = current_time
            except Exception as e:
                # Log but don't break the request if activity tracking fails
                user_identifier = get_user_identifier(request)
                _logger.warning(f"Failed to update activity for user {user_identifier} on {request.path}: {str(e)}")

        return response


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


class FixRequestHostMiddleware:
    """
    Middleware to fix request host for email URL generation.
    This ensures that request.build_absolute_uri() uses the Site domain
    instead of the request's actual host when they don't match.
    """
    
    def __init__(self, get_response):
        self.get_response = get_response
    
    def __call__(self, request):
        # Fix HTTP_HOST for password reset and email-related requests
        # This ensures allauth uses the correct domain when building URLs
        if (request.path.startswith('/accounts/password/reset/') or 
            request.path.startswith('/accounts/email/') or
            request.path.startswith('/api/user/email/')):
            site_domain = get_required_setting('SITE_DOMAIN')
            # Always override HTTP_HOST to use Site domain
            request.META['HTTP_HOST'] = site_domain
            # Also override get_host() method
            def fixed_get_host():
                return site_domain
            request.get_host = fixed_get_host
            # Override build_absolute_uri to use Site domain
            def fixed_build_absolute_uri(location=None):
                if location is None:
                    location = request.get_full_path()
                if not location.startswith('/'):
                    return location
                protocol = 'https' if not get_setting('DEBUG', False) else 'http'
                return f"{protocol}://{site_domain}{location}"
            request.build_absolute_uri = fixed_build_absolute_uri
        
        return self.get_response(request)


class CustomHeaderMiddleware:
    """Middleware to add security headers including CORS and CSP."""

    def __init__(self, get_response):
        self.get_response = get_response
        self._cors_origins = None
        self._csp_connect_src = None

    def _get_cors_origins(self):
        """Get list of allowed CORS origins (cached)."""
        if self._cors_origins is None:

            origins = set()

            # Add site's own domain
            protocol = 'https' if not get_setting('DEBUG', False) else 'http'
            site_origin = f"{protocol}://{get_required_setting('SITE_DOMAIN')}"
            origins.add(site_origin)

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
            protocol = 'https' if not get_setting('DEBUG', False) else 'http'
            response['Access-Control-Allow-Origin'] = f"{protocol}://{get_required_setting('SITE_DOMAIN')}"

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
        is_tile_request = request.path.startswith('/api/tiles/')
        
        # Prevent session from being saved for tile requests
        # This stops the session middleware from setting Set-Cookie headers
        if is_tile_request and hasattr(request, 'session'):
            # Mark session as not modified to prevent saving
            request.session.modified = False
        
        response = self.get_response(request)

        # Remove Set-Cookie headers from tile proxy responses (fallback safety measure)
        # TileCacheSessionMiddleware should handle this, but we do it here too as a safety net
        # Cloudflare does not cache responses with Set-Cookie headers
        if is_tile_request:
            # Clear all cookies from the response (Django stores them in response.cookies)
            response.cookies.clear()
            
            # Remove any Set-Cookie headers that might have been set
            while response.has_header('Set-Cookie'):
                del response['Set-Cookie']
            
        # Set CORS headers on all responses
        self._set_cors_headers(request, response)

        # For tile requests, ensure Access-Control-Allow-Credentials is not set
        # to maximize cacheability (CORS spec also requires credentials=false when
        # Access-Control-Allow-Origin is *)
        if is_tile_request and response.has_header('Access-Control-Allow-Credentials'):
            del response['Access-Control-Allow-Credentials']

        # Set CSP headers on HTML responses
        content_type = response.get('Content-Type', '')
        if 'text/html' in content_type:
            self._set_csp_headers(response)

        return response
