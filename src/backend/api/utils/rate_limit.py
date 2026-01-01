"""
Custom Redis-based rate limiting decorator for CalTopo API endpoints.

Uses Django's cache framework to access Redis, implementing a sliding window
rate limiter that allows 1 request per second per user per route.
"""
import time
from functools import wraps
from typing import Callable
from django.core.cache import caches
from django.http import HttpRequest, JsonResponse

from api.utils.responses import error_response
from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger('RateLimit')

# Rate limit: 1 request per second
RATE_LIMIT_SECONDS = 1.0


def caltopo_rate_limit(route_name: str = None):
    """
    Decorator to rate limit CalTopo API endpoints.
    
    Limits requests to 1 per second per user per route using Redis.
    Uses lazy cache access to avoid import-time issues.
    
    Args:
        route_name: Optional route identifier. If not provided, uses the function name.
    
    Usage:
        @caltopo_rate_limit('list_maps')
        def list_caltopo_maps(request):
            ...
    """
    def decorator(view_func: Callable) -> Callable:
        @wraps(view_func)
        def _wrapped_view(request: HttpRequest, *args, **kwargs) -> JsonResponse:
            # Get route identifier
            route_id = route_name or view_func.__name__
            
            # Get user ID (must be authenticated at this point)
            if not request.user.is_authenticated:
                # Should not happen if api_or_login_required_401 is used, but be safe
                return error_response('Authentication required', code=401)
            
            user_id = request.user.id
            
            # Get current time window (per-second granularity)
            current_time = time.time()
            window_timestamp = int(current_time)  # Round down to second
            
            # Build cache key: ratelimit:{user_id}:{route_name}:{window_timestamp}
            # The cache already has KEY_PREFIX='ratelimit', so we just need the rest
            cache_key = f"{user_id}:{route_id}:{window_timestamp}"
            
            # Access cache lazily (only when decorator is called, not at import time)
            try:
                cache = caches['rate_limiting']
            except Exception as e:
                # If cache is unavailable, log warning but allow request (fail open)
                _logger.warning(f"Rate limiting cache unavailable: {e}. Allowing request.")
                return view_func(request, *args, **kwargs)
            
            # Check rate limit using Redis operations
            # This works across multiple processes
            try:
                # Try to get the current count
                current_count = cache.get(cache_key, 0)
                
                if current_count >= 1:
                    # Rate limit exceeded
                    time_left = 1.0 - (current_time - window_timestamp)
                    if time_left < 0:
                        time_left = 0
                    
                    _logger.debug(
                        f"Rate limit exceeded for user {user_id} on route {route_id}. "
                        f"Wait {time_left:.1f} seconds."
                    )
                    
                    return error_response(
                        f'Rate limit exceeded. Please wait {time_left:.1f} seconds before making another request to CalTopo.',
                        code=429
                    )
                
                # Increment counter atomically
                # If key doesn't exist, add() creates it with value 1 and expiration
                # If key exists, incr() increments it
                if current_count == 0:
                    # First request in this window - use add() to set with expiration atomically
                    added = cache.add(cache_key, 1, timeout=int(RATE_LIMIT_SECONDS) + 1)  # +1 for safety
                    if not added:
                        # Key was added by another process between get() and add()
                        # This means rate limit is exceeded
                        time_left = 1.0 - (current_time - window_timestamp)
                        if time_left < 0:
                            time_left = 0
                        return error_response(
                            f'Rate limit exceeded. Please wait {time_left:.1f} seconds before making another request to CalTopo.',
                            code=429
                        )
                else:
                    # Should not happen with our logic (we check >= 1 above), but handle it
                    cache.incr(cache_key)
                
            except Exception as e:
                # If Redis operation fails, log warning but allow request (fail open)
                _logger.warning(
                    f"Rate limiting check failed for user {user_id} on route {route_id}: {e}. "
                    f"Allowing request."
                )
                return view_func(request, *args, **kwargs)
            
            # Rate limit check passed, proceed with request
            return view_func(request, *args, **kwargs)
        
        return _wrapped_view
    return decorator

