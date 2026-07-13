"""
Shared types and utilities for forward reverse_geocoding (place search) backends.
"""
import hashlib

from django.conf import settings


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
    mode = settings.GEOCODING_SEARCH_MODE
    normalized = query.strip().lower()
    query_hash = hashlib.md5(normalized.encode('utf-8')).hexdigest()
    return f"reverse_geocoding:{mode}:{query_hash}"
