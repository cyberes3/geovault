"""
Backwards-compatibility wrapper for reverse geocoding.

This module re-exports the new functional API for backwards compatibility.
All functionality has been refactored into separate modules:

- location_tags.py: Main public API (batch_reverse_geocode_coordinates, get_location_tags)
- admin_boundaries.py: Administrative hierarchy lookup
- protected_areas.py: Protected area detection
- nearby_places.py: Cities and lakes proximity search
- ski_resorts.py: Ski resort detection
- overpass_api.py: Low-level Overpass API client
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
from geo_lib.geocoding.location_tags import (
    batch_reverse_geocode_coordinates,
    get_location_tags,
    ReverseGeocodingLogMessage,
)

# Deprecated: For backwards compatibility only
# This function is no longer needed since we don't use a singleton class
def get_reverse_geocoding_service():
    """
    DEPRECATED: Returns a dummy object for backwards compatibility.
    
    This function exists only for backwards compatibility with old code.
    New code should call batch_reverse_geocode_coordinates() or get_location_tags() directly.
    
    The old class-based API has been replaced with module-level functions.
    """
    class _DeprecatedServiceWrapper:
        """Wrapper that delegates to the new functional API."""
        
        def batch_reverse_geocode_coordinates(self, coordinates):
            return batch_reverse_geocode_coordinates(coordinates)
        
        def get_location_tags(self, latitude, longitude):
            return get_location_tags(latitude, longitude)
    
    return _DeprecatedServiceWrapper()


# For any old code that might reference ReverseGeocodingService class directly
class ReverseGeocodingService:
    """
    DEPRECATED: Use module-level functions instead.
    
    This class exists only for backwards compatibility with tests and old code.
    The actual implementation has been refactored into module-level functions.
    """
    
    def __init__(self):
        """Initialize (does nothing, maintained for compatibility)."""
        pass
    
    def batch_reverse_geocode_coordinates(self, coordinates):
        """Delegate to the new functional API."""
        return batch_reverse_geocode_coordinates(coordinates)
    
    def get_location_tags(self, latitude, longitude):
        """Delegate to the new functional API."""
        return get_location_tags(latitude, longitude)
    
    # Legacy methods that old code might reference
    def _get_admin_hierarchy(self, latitude, longitude):
        """DEPRECATED: Use admin_boundaries.get_admin_hierarchy() directly."""
        from geo_lib.geocoding.admin_boundaries import get_admin_hierarchy
        return get_admin_hierarchy(latitude, longitude)
    
    def _find_nearby_cities(self, latitude, longitude, threshold_miles):
        """DEPRECATED: Use nearby_places.find_nearby_cities() directly."""
        from geo_lib.geocoding.nearby_places import find_nearby_cities
        return find_nearby_cities(latitude, longitude, threshold_miles)
    
    def _get_protected_areas(self, latitude, longitude):
        """DEPRECATED: Use protected_areas.get_protected_areas() directly."""
        from geo_lib.geocoding.protected_areas import get_protected_areas
        return get_protected_areas(latitude, longitude)
    
    def _search_nearby_lakes(self, latitude, longitude, proximity_miles):
        """DEPRECATED: Use nearby_places.search_nearby_lakes() directly."""
        from geo_lib.geocoding.nearby_places import search_nearby_lakes
        return search_nearby_lakes(latitude, longitude, proximity_miles)
    
    def _get_admin_and_protected_areas(self, latitude, longitude):
        """DEPRECATED: Combined query no longer exists, use separate functions."""
        from geo_lib.geocoding.admin_boundaries import get_admin_hierarchy
        from geo_lib.geocoding.protected_areas import get_protected_areas
        return get_admin_hierarchy(latitude, longitude), get_protected_areas(latitude, longitude)
    
    def _query_overpass(self, query, max_retries=3, latitude=None, longitude=None):
        """DEPRECATED: Use overpass_api.query_overpass() directly."""
        from geo_lib.geocoding.overpass_api import query_overpass
        return query_overpass(query, max_retries, latitude, longitude)


__all__ = [
    'batch_reverse_geocode_coordinates',
    'get_location_tags',
    'ReverseGeocodingLogMessage',
    'get_reverse_geocoding_service',  # Deprecated
    'ReverseGeocodingService',  # Deprecated
]
