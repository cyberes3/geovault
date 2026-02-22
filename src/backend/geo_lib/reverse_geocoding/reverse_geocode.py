"""
Public API for reverse geocoding.

Re-exports from location_tags. Other modules:

- location_tags.py: Main public API (batch_reverse_geocode_coordinates, get_location_tags)
- areas_server_client.py: HTTP client for areas server (admin, protected areas, nearby lakes, ocean)
- admin_boundaries.py: Administrative hierarchy parser (tests only; production uses areas server)
- protected_areas.py: Protected area classification and parser (tests only; production uses areas server)
- ski_resorts.py: Ski resort detection
- cache.py: Caching utilities
- osm_tags.py: OSM tag utilities

PUBLIC API:
    batch_reverse_geocode_coordinates(coordinates) -> Dict
        Main entry point: Batch reverse geocode multiple coordinates
        
    get_location_tags(lat, lon) -> Tuple[List[str], List[ReverseGeocodingLogMessage]]
        Generate tags for a single coordinate
        
    ReverseGeocodingLogMessage
        Log message dataclass
"""

# Re-export the public API from the new modules
from geo_lib.reverse_geocoding.location_tags import (
    batch_reverse_geocode_coordinates,
    get_location_tags,
    ReverseGeocodingLogMessage,
)

__all__ = [
    'batch_reverse_geocode_coordinates',
    'get_location_tags',
    'ReverseGeocodingLogMessage',
]
