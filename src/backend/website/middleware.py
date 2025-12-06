import time
import traceback
from urllib.parse import urlparse

from django.conf import settings
from django.contrib.auth.models import AnonymousUser

from geo_lib.logging.console import get_access_logger
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier
from users.models import UserProfile
from website.settings import get_tile_source_origins

access_logger = get_access_logger()

# In-memory cache for activity tracking throttling
# Format: {user_id: last_update_timestamp}
_activity_tracking_cache = {}
# Throttle activity updates to at most once per 30 seconds per user
ACTIVITY_TRACKING_THROTTLE_SECONDS = 30


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
            access_logger.error(traceback_str)
            
            # Re-raise the exception so Django's exception handling can process it
            # This ensures got_request_exception signal fires and handler500 is called
            # Response formatting is handled by custom_exception_handler in exception_handler.py
            raise
        
        # Log API requests and errors
        if request.path.startswith('/api/'):
            query_string = request.GET.urlencode()
            if query_string:
                log_msg = f"{request.method} {request.path}?{query_string} - {user_identifier}@{client_ip}"
            else:
                log_msg = f"{request.method} {request.path} - {user_identifier}@{client_ip}"
            
            if response.status_code >= 400:
                # Log errors with status
                access_logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                # Log successful requests
                access_logger.info(log_msg)
        
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
                access_logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                access_logger.info(log_msg)
        
        # Log favicon requests (no username)
        elif request.path == '/favicon.ico':
            # Get file size if available
            content_length = _get_content_length(response)
            
            if content_length:
                log_msg = f"{request.method} {request.path} - {client_ip} - {content_length} bytes"
            else:
                log_msg = f"{request.method} {request.path} - {client_ip}"
            
            if response.status_code >= 400:
                access_logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                access_logger.info(log_msg)
        
        # Log root and other non-API requests (no username)
        elif not request.path.startswith('/api/') and not request.path.startswith('/admin/') and not request.path.startswith('/account/'):
            # This catches root path and other non-API routes
            # Skip static files as they're handled above, but this is a fallback
            if not request.path.startswith('/static/'):
                log_msg = f"{request.method} {request.path} - {client_ip}"
                if response.status_code >= 400:
                    access_logger.warning(f"{log_msg} - Status: {response.status_code}")
                else:
                    access_logger.info(log_msg)
        
        return response


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
                access_logger.warning(f"Failed to update activity for user {user_identifier} on {request.path}: {str(e)}")
        
        return response


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
            protocol = 'https' if not settings.DEBUG else 'http'
            site_origin = f"{protocol}://{settings.SITE_DOMAIN}"
            origins.add(site_origin)
            
            # Add external tile source origins
            try:
                tile_origins = get_tile_source_origins()
                origins.update(tile_origins)
            except Exception as e:
                # Log but don't fail if tile sources aren't available yet
                access_logger.debug(f"Could not load tile source origins: {e}")
            
            # Add user-configured additional origins
            additional_origins = getattr(settings, 'ADDITIONAL_CORS_ORIGINS', [])
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
            protocol = 'https' if not settings.DEBUG else 'http'
            response['Access-Control-Allow-Origin'] = f"{protocol}://{settings.SITE_DOMAIN}"
        
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
        response = self.get_response(request)
        
        # Set CORS headers on all responses
        self._set_cors_headers(request, response)
        
        # Set CSP headers on HTML responses
        content_type = response.get('Content-Type', '')
        if 'text/html' in content_type:
            self._set_csp_headers(response)
        
        return response
