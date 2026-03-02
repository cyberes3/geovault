"""
Public API for reverse geocoding.

Re-exports from location_tags. Other modules:

- location_tags.py: Main public API (batch_reverse_geocode_coordinates, reverse_geocode_coordinates)
- areas_server_client.py: HTTP client for areas server (admin, protected areas, lakes, ocean, ski_resort)
- admin_boundaries.py: Administrative hierarchy parser (tests only; production uses areas server)
- protected_areas.py: Protected area classification and parser (tests only; production uses areas server)
- cache.py: Caching utilities
- osm_tags.py: OSM tag utilities

PUBLIC API:
    batch_reverse_geocode_coordinates(coordinates) -> Dict
        Main entry point: Batch reverse geocode multiple coordinates
        
    reverse_geocode_coordinates(lat, lon) -> Tuple[List[str], List[ReverseGeocodingLogMessage]]
        Generate tags for a single coordinate (GET)
        
    ReverseGeocodingLogMessage
        Log message dataclass
"""

# Re-export the public API from the new modules
from geo_lib.reverse_geocoding.location_tags import (
    ReverseGeocodingLogMessage,
    batch_reverse_geocode_coordinates,
    reverse_geocode_coordinates,
)

__all__ = [
    'ReverseGeocodingLogMessage',
    'batch_reverse_geocode_coordinates',
    'reverse_geocode_coordinates',
]
