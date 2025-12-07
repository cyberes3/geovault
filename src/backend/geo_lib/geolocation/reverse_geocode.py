"""
Reverse geocoding service.
Note: Reverse geocoding is currently disabled and will return empty data.
This will be reimplemented using MapTiler in the future.
"""
import math
from typing import Optional, Dict, Any, List

from geo_lib.logging.console import get_geocode_logger

logger = get_geocode_logger()


def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculate the great circle distance between two points on Earth in miles."""
    R = 3958.8
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c


class ReverseGeocodingService:
    def __init__(self):
        """Initialize the reverse geocoding service."""
        pass

    def is_point_in_water(self, latitude: float, longitude: float) -> bool:
        """
        Check if a point is in water.
        
        Returns:
            False (placeholder - will be reimplemented later)
        """
        return False

    def reverse_geocode(self, latitude: float, longitude: float, import_log=None) -> Optional[Dict[str, Any]]:
        """
        Reverse geocode a coordinate to get location information.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            import_log: Optional ImportLog for database logging (unused)
        
        Returns:
            None (placeholder - will be reimplemented later)
        """
        return None

    def search_protected_areas(self, latitude: float, longitude: float) -> List[Dict[str, Any]]:
        """
        Search for protected areas at a given coordinate.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
        
        Returns:
            Empty list (placeholder - will be reimplemented later)
        """
        return []

    def search_lakes(self, latitude: float, longitude: float, proximity_miles: float = 1.0) -> List[Dict[str, Any]]:
        """
        Search for lakes and water bodies within proximity_miles of the point.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            proximity_miles: Distance threshold in miles
        
        Returns:
            Empty list (placeholder - will be reimplemented later)
        """
        return []

    def check_city_proximity(self, latitude: float, longitude: float, threshold_miles: float, import_log=None) -> Optional[Dict[str, Any]]:
        """
        Check for nearby cities/towns within threshold_miles.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            threshold_miles: Distance threshold in miles
            import_log: Optional ImportLog for database logging (unused)
        
        Returns:
            None (placeholder - will be reimplemented later)
        """
        return None

    def get_location_tags(self, latitude: float, longitude: float, import_log=None) -> List[str]:
        """
        Generate location tags for a given coordinate.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            import_log: Optional ImportLog for database logging (unused)
        
        Returns:
            Empty list (placeholder - will be reimplemented later)
        """
        return []


_reverse_geocoding_service = None


def get_reverse_geocoding_service() -> ReverseGeocodingService:
    """Get or create the singleton reverse geocoding service instance."""
    global _reverse_geocoding_service
    if _reverse_geocoding_service is None:
        _reverse_geocoding_service = ReverseGeocodingService()
    return _reverse_geocoding_service
