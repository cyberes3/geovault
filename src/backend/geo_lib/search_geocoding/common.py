"""
Shared types and utilities for forward reverse_geocoding (place search) backends.
"""
from django.conf import settings

from geo_lib.perf.cache_keys import GeocodeKey


class GeocodingBackendError(Exception):
    """Raised by search backends when the provider returns an error (e.g. all requests failed)."""
    pass


# Cache TTL: 7 days in seconds
GEOCODING_CACHE_TTL = 604800


def get_geocoding_cache_key(query: str) -> str:
    """
    Generate cache key for forward reverse_geocoding query.
    Mode-aware so switching provider does not serve stale results.

    Args:
        query: Search query

    Returns:
        Cache key string safe for memcached
    """
    return str(GeocodeKey(settings.GEOCODING_SEARCH_MODE, query))
