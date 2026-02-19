"""
Forward reverse_geocoding API view with pluggable backends (MapTiler or Google per geocoding_search_mode).
Provides place search functionality with server-side caching.
"""
import requests
from django.core.cache import cache
from django.views.decorators.http import require_http_methods

from api.utils.responses import error_response, success_response
from geo_lib.logging.console import get_tagged_logger
from geo_lib.search_geocoding.backends import get_geocoding_not_available_message, get_search_backend
from geo_lib.search_geocoding.common import (
    GEOCODING_CACHE_TTL,
    GeocodingBackendError,
    get_geocoding_cache_key,
)
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger(__name__)


@require_http_methods(["GET"])
@api_or_login_required_401()
def geocoding_search(request):
    """
    Search for places using the configured backend (MapTiler or Google per geocoding_search_mode).

    Query parameters:
        q: Search query string (required)

    Returns:
        JSON response with reverse_geocoding results or error message

    Caching:
        Results are cached server-side for 7 days. Cache key includes mode so switching provider does not serve stale results.
    """
    query = request.GET.get('q', '').strip()

    if not query:
        _logger.warning("geocoding_search: query parameter 'q' is required")
        return error_response("Query parameter 'q' is required", code=400)

    not_available = get_geocoding_not_available_message()
    if not_available is not None:
        _logger.warning("geocoding_search: %s (code=503)", not_available)
        return error_response(not_available, code=503)

    cache_key = get_geocoding_cache_key(query)
    cached_result = cache.get(cache_key)
    if cached_result is not None:
        response = success_response(data={'data': cached_result})
        response['Cache-Control'] = f'public, max-age={GEOCODING_CACHE_TTL}'
        return response

    backend = get_search_backend()
    try:
        result = backend(query)
    except requests.exceptions.Timeout:
        _logger.warning("geocoding_search: Forward reverse_geocoding API request timed out (code=504)")
        return error_response("Forward reverse_geocoding API request timed out", code=504)
    except GeocodingBackendError as e:
        _logger.warning("geocoding_search: %s (code=400)", e)
        return error_response(str(e), code=400)

    cache.set(cache_key, result, GEOCODING_CACHE_TTL)
    response = success_response(data={'data': result})
    response['Cache-Control'] = f'public, max-age={GEOCODING_CACHE_TTL}'
    return response
