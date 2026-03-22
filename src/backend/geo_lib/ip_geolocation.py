"""
IP-based geolocation service using MaxMind GeoIP2 database.
"""
import os
from typing import Optional, Dict, Any

import geoip2.database
import geoip2.errors

from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_required_setting

logger = get_tagged_logger('geocode')


class IPGeolocationService:
    """
    Service for determining user location based on IP address using MaxMind GeoIP2 database.
    """

    def __init__(self):
        """
        Initialize the IP geolocation service.
        """

        self.database_path = get_required_setting('MAXMIND_DATABASE_PATH')
        self.reader = None
        self._initialize_reader()

    def _initialize_reader(self):
        """Initialize the GeoIP2 database reader."""
        if os.path.exists(self.database_path):
            self.reader = geoip2.database.Reader(self.database_path)
        else:
            raise Exception(f"MaxMind GeoIP2 database not found at {self.database_path}")

    def get_location_from_ip(self, ip_address: str) -> Optional[Dict[str, Any]]:
        """
        Get location information from an IP address.
        
        Args:
            ip_address: The IP address to look up
            
        Returns:
            Dictionary containing location information or None if lookup fails
        """
        assert self.reader

        # Local / private IPs are not in GeoIP; do not invent a map center (frontend fits to data).
        if self._is_private_ip(ip_address):
            return None

        try:
            response = self.reader.city(ip_address)
            return {
                'ip': ip_address,
                'country': response.country.name,
                'country_code': response.country.iso_code,
                'state': response.subdivisions.most_specific.name if response.subdivisions else None,
                'state_code': response.subdivisions.most_specific.iso_code if response.subdivisions else None,
                'city': response.city.name,
                'latitude': float(response.location.latitude) if response.location.latitude else None,
                'longitude': float(response.location.longitude) if response.location.longitude else None,
            }
        except geoip2.errors.AddressNotFoundError:
            logger.warning("IP address %s not found in database", ip_address)
            return None

    def _is_private_ip(self, ip_address: str) -> bool:
        """
        Check if an IP address is private/local.
        
        Args:
            ip_address: The IP address to check
            
        Returns:
            True if the IP is private/local
        """
        if ip_address in ['127.0.0.1', '::1', 'localhost']:
            return True

        # Check for private IP ranges
        parts = ip_address.split('.')
        if len(parts) == 4:
            try:
                first_octet = int(parts[0])
                if first_octet == 10:  # 10.0.0.0/8
                    return True
                elif first_octet == 172 and 16 <= int(parts[1]) <= 31:  # 172.16.0.0/12
                    return True
                elif first_octet == 192 and int(parts[1]) == 168:  # 192.168.0.0/16
                    return True
            except ValueError:
                pass

        return False

    def get_client_ip(self, request) -> str:
        """
        Extract the client IP address from a Django request.
        
        Args:
            request: Django request object
            
        Returns:
            Client IP address string
        """
        # Check for forwarded IP (when behind a proxy/load balancer)
        x_forwarded_for = request.META.get('HTTP_X_FORWARDED_FOR')
        if x_forwarded_for:
            # Take the first IP in the chain
            ip = x_forwarded_for.split(',')[0].strip()
        else:
            ip = request.META.get('REMOTE_ADDR', '127.0.0.1')

        return ip

    def close(self):
        """Close the database reader."""
        if self.reader:
            self.reader.close()
            self.reader = None


# Global instance
_geolocation_service = None


def get_geolocation_service() -> IPGeolocationService:
    """
    Get the global geolocation service instance.
    
    Returns:
        IPGeolocationService instance
    """
    global _geolocation_service
    if _geolocation_service is None:
        _geolocation_service = IPGeolocationService()
    return _geolocation_service
