"""
Registry of forward reverse_geocoding backends (MapTiler, Google).
"""
from geo_lib.search_geocoding.constants import GEOCODING_SEARCH_MODES, GOOGLE, MAPTILER
from geo_lib.search_geocoding.google import _search_google
from geo_lib.search_geocoding.maptiler import _search_maptiler
from website.config_loader import get_config_loader

_SEARCH_BACKENDS = {
    MAPTILER: _search_maptiler,
    GOOGLE: _search_google,
}


def get_search_backend():
    """Return the search backend callable for the given mode. Unknown mode raises ValueError."""
    mode = get_config_loader().get_geocoding_search_mode()
    if mode not in _SEARCH_BACKENDS:
        raise ValueError(f"Unknown reverse_geocoding search mode: {mode!r}")
    return _SEARCH_BACKENDS[mode]


def list_search_backends():
    return list(GEOCODING_SEARCH_MODES)


def get_geocoding_not_available_message():
    """
    Return an error message when forward geocoding is not available, or None if available.
    Caller can return this message with 503 so the client sees which backend is missing.
    """
    mode = get_config_loader().get_geocoding_search_mode()
    if mode not in _SEARCH_BACKENDS:
        return "Forward geocoding is not configured (geocoding_search_mode not set or invalid)."
    if mode == MAPTILER:
        api_key = get_config_loader().get_maptiler_api_key()
        if not api_key:
            return "MapTiler geocoding is not available (API key not configured)."
    elif mode == GOOGLE:
        api_key = get_config_loader().get_google_api_key()
        if not api_key:
            return "Google geocoding is not available (API key not configured)."
    return None


def check_geocoding_enabled():
    return get_geocoding_not_available_message() is None
