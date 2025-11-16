"""
IP-based geolocation service using MaxMind GeoIP2 database.
"""
import os
import traceback
from typing import Optional, Dict, Any
import geoip2.database
import geoip2.errors
from geo_lib.logging.console import get_geocode_logger

logger = get_geocode_logger()


class IPGeolocationService:
    """
    Service for determining user location based on IP address using MaxMind GeoIP2 database.
    """
    
    def __init__(self, database_path: Optional[str] = None):
        """
        Initialize the IP geolocation service.
        
        Args:
            database_path: Path to the MaxMind GeoIP2 City database file.
                          If None, will look for it in Django settings or default location.
        """
        if database_path is None:
            # Try to get from Django settings first
            try:
                from django.conf import settings
                database_path = getattr(settings, 'MAXMIND_DATABASE_PATH', None)
            except Exception:
                # Django not initialized yet, fall back to environment variable
                database_path = None
            
            # Fall back to environment variable if not in settings
            if database_path is None:
                database_path = os.environ.get('MAXMIND_DATABASE_PATH', 
                                             '/var/lib/GeoIP/GeoLite2-Country.mmdb')
        
        self.database_path = database_path
        self.reader = None
        self._initialize_reader()
    
    def _initialize_reader(self):
        """Initialize the GeoIP2 database reader."""
        try:
            if os.path.exists(self.database_path):
                self.reader = geoip2.database.Reader(self.database_path)
                # Database loaded successfully, no need to log
            else:
                logger.warning(f"MaxMind GeoIP2 database not found at {self.database_path}")
                self.reader = None
        except Exception as e:
            logger.error(f"Failed to initialize MaxMind GeoIP2 database: {e}")
            self.reader = None
    
    def get_location_from_ip(self, ip_address: str) -> Optional[Dict[str, Any]]:
        """
        Get location information from an IP address.
        
        Args:
            ip_address: The IP address to look up
            
        Returns:
            Dictionary containing location information or None if lookup fails
        """
        if not self.reader:
            logger.warning("MaxMind GeoIP2 database not available")
            return None
        
        # Handle localhost and private IP addresses
        if self._is_private_ip(ip_address):
            # Private IP detected, returning default location (normal operation)
            return self._get_default_location()
        
        try:
            # Try city database first, fall back to country database
            try:
                response = self.reader.city(ip_address)
                location_data = {
                    'ip': ip_address,
                    'country': response.country.name,
                    'country_code': response.country.iso_code,
                    'state': response.subdivisions.most_specific.name if response.subdivisions else None,
                    'state_code': response.subdivisions.most_specific.iso_code if response.subdivisions else None,
                    'city': response.city.name,
                    'postal_code': response.postal.code,
                    'latitude': float(response.location.latitude) if response.location.latitude else None,
                    'longitude': float(response.location.longitude) if response.location.longitude else None,
                    'accuracy_radius': response.location.accuracy_radius,
                    'timezone': response.location.time_zone,
                    'continent': response.continent.name,
                    'continent_code': response.continent.code
                }
                # Location lookup successful (normal operation)
            except AttributeError:
                # Fall back to country database
                response = self.reader.country(ip_address)
                location_data = {
                    'ip': ip_address,
                    'country': response.country.name,
                    'country_code': response.country.iso_code,
                    'state': None,
                    'state_code': None,
                    'city': None,
                    'postal_code': None,
                    'latitude': None,
                    'longitude': None,
                    'accuracy_radius': None,
                    'timezone': None,
                    'continent': response.continent.name,
                    'continent_code': response.continent.code
                }
                # Location lookup successful (country-level only, normal operation)
            
            return location_data
            
        except geoip2.errors.AddressNotFoundError:
            logger.warning(f"IP address {ip_address} not found in database")
            return None
        except Exception as e:
            logger.error(f"Error looking up IP {ip_address}: {e}")
            logger.error(f"IP lookup error traceback: {traceback.format_exc()}")
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
    
    def _get_default_location(self) -> Dict[str, Any]:
        """
        Get default location for private/local IP addresses and geolocation failures.
        
        Returns:
            Dictionary with default location (Denver, Colorado)
        """
        return {
            'ip': '127.0.0.1',
            'country': 'United States',
            'country_code': 'US',
            'state': 'Colorado',
            'state_code': 'CO',
            'city': 'Denver',
            'postal_code': None,
            'latitude': 39.7392,
            'longitude': -104.9903,
            'accuracy_radius': None,
            'timezone': 'America/Denver',
            'continent': 'North America',
            'continent_code': 'NA',
            'is_default': True
        }
    
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
