"""
Geocoding module constants.

Central location for all geocoding-related constants to avoid duplication.
"""

# Cache TTL: 30 days in seconds
REVERSE_GEOCODING_CACHE_TTL = 30 * 24 * 60 * 60

# Coordinate rounding precision for cache keys (~111 meters)
COORDINATE_PRECISION = 3
