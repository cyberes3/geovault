"""
Registry of forward reverse_geocoding backends (MapTiler, Google).
"""
from django.conf import settings

from geo_lib.search_geocoding.constants import GOOGLE, MAPTILER
from geo_lib.search_geocoding.google import _search_google
from geo_lib.search_geocoding.maptiler import _search_maptiler

_SEARCH_BACKENDS = {
    MAPTILER: _search_maptiler,
    GOOGLE: _search_google,
}


def get_search_backend():
    """Return the search backend callable for the given mode. Unknown mode raises ValueError."""
    mode = settings.GEOCODING_SEARCH_MODE
    if mode not in _SEARCH_BACKENDS:
        raise ValueError(f"Unknown reverse_geocoding search mode: {mode!r}")
    return _SEARCH_BACKENDS[mode]


def get_geocoding_not_available_message():
    """
    Return an error message when forward geocoding is not available, or None if available.
    Caller can return this message with 503 so the client sees which backend is missing.
    """
    mode = settings.GEOCODING_SEARCH_MODE
    if mode not in _SEARCH_BACKENDS:
        return "Forward geocoding is not configured (geocoding_search_mode not set or invalid)."
    if mode == MAPTILER:
        if not settings.MAPTILER_API_KEY:
            return "MapTiler geocoding is not available (API key not configured)."
    elif mode == GOOGLE:
        if not settings.GOOGLE_GEOCODING_API_KEY:
            return "Google geocoding is not available (API key not configured)."
    return None
