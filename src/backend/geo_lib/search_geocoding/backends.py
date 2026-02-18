"""
Registry of forward reverse_geocoding backends (MapTiler, Google).
"""
from geo_lib.search_geocoding.google import _search_google
from geo_lib.search_geocoding.maptiler import _search_maptiler
from website.config_loader import get_config_loader

_SEARCH_BACKENDS = {
    'maptiler': _search_maptiler,
    'google': _search_google,
}


def get_search_backend():
    """Return the search backend callable for the given mode. Unknown mode raises ValueError."""
    mode = get_config_loader().get_geocoding_search_mode()
    if mode not in _SEARCH_BACKENDS:
        raise ValueError(f"Unknown reverse_geocoding search mode: {mode!r}")
    return _SEARCH_BACKENDS[mode]


def list_search_backends():
    return list(_SEARCH_BACKENDS.keys())


def check_geocoding_enabled():
    mode = get_search_backend()
    if mode == 'maptiler':
        api_key = get_config_loader().get_maptiler_api_key()
    elif mode == 'google':
        api_key = get_config_loader().get_google_api_key()
    else:
        return False

    if not api_key:
        return False
    return True
