from django.core.cache import caches

from geo_lib.spatial.coordinates import round_coordinate

_REVERSE_GEOCODING_CACHE = caches['reverse_geocoding']


def _get_cache_key(latitude: float, longitude: float, prefix: str = "reverse_geocode") -> str:
    """
    Generate cache key for coordinate (rounded to ~111m precision).

    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        prefix: Cache key prefix

    Returns:
        Cache key string
    """
    lat_rounded, lon_rounded = round_coordinate(latitude, longitude)
    return f"{prefix}:{lat_rounded},{lon_rounded}"
