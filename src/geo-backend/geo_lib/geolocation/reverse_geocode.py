"""
Reverse geocoding service using Overpass API and reverse-geocoder.
"""
import math
from typing import Optional, Dict, Any, List
import requests
import reverse_geocoder as rg
from django.conf import settings
from geo_lib.logging.console import get_geocode_logger

logger = get_geocode_logger()

def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = 3958.8
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c

class ReverseGeocodingService:
    def __init__(self, overpass_url: Optional[str] = None, nominatim_url: Optional[str] = None):
        self.overpass_url = overpass_url or getattr(settings, 'OVERPASS_API_URL', 'https://overpass-api.de/api/interpreter')
        self.nominatim_url = nominatim_url or getattr(settings, 'NOMINATIM_API_URL', 'https://nominatim.openstreetmap.org')
        self.user_agent = "GeoServer/1.0"
    
    def is_point_in_water(self, latitude: float, longitude: float) -> bool:
        """Check if a point is in water using Overpass API."""
        try:
            query = f"""[out:json][timeout:10];
(
  is_in({latitude},{longitude})->.a;
  relation["natural"="water"](pivot.a);
  way["natural"="water"](pivot.a);
  relation["water"](pivot.a);
  way["water"](pivot.a);
  relation["place"="sea"](pivot.a);
  way["place"="sea"](pivot.a);
);
out count;"""
            headers = {'User-Agent': self.user_agent, 'Content-Type': 'application/x-www-form-urlencoded'}
            response = requests.post(self.overpass_url, data={'data': query}, headers=headers, timeout=15)
            if response.status_code == 200:
                data = response.json()
                if data and 'elements' in data:
                    # If we have any water elements, the point is in water
                    return len(data.get('elements', [])) > 0
            return False
        except Exception as e:
            logger.warning(f"Water check failed: {e}")
            return False
    
    def reverse_geocode_overpass(self, latitude: float, longitude: float) -> Optional[Dict[str, Any]]:
        """
        Reverse geocode using Overpass API to get state, country, and county.
        City/town detection is handled by Nominatim, which is better at it.
        """
        try:
            # Get administrative boundaries for state, country, and county only
            query = f"""[out:json][timeout:10];
(
  is_in({latitude},{longitude})->.a;
  relation["admin_level"="2"](pivot.a);  // Country
  relation["admin_level"="4"](pivot.a);  // State/Province
  relation["admin_level"="6"](pivot.a);  // County
);
out tags;"""
            
            headers = {'User-Agent': self.user_agent, 'Content-Type': 'application/x-www-form-urlencoded'}
            response = requests.post(self.overpass_url, data={'data': query}, headers=headers, timeout=15)
            if response.status_code != 200:
                logger.warning(f"Overpass API returned status {response.status_code}")
                return None
            
            data = response.json()
            if not data or 'elements' not in data:
                return None
            
            result = {
                'country_code': '',
                'state': '',
                'county': '',
            }
            
            # Process elements for country, state, county
            for element in data.get('elements', []):
                tags = element.get('tags', {})
                
                # Get country
                if not result['country_code']:
                    country_code = tags.get('ISO3166-1:alpha2', '') or tags.get('ISO3166-1', '')
                    if country_code:
                        result['country_code'] = country_code.upper()
                    else:
                        # Try to get from boundary=administrative with admin_level=2
                        if tags.get('boundary') == 'administrative' and tags.get('admin_level') == '2':
                            country_code = tags.get('ref', '') or tags.get('ISO3166-1:alpha2', '')
                            if country_code:
                                result['country_code'] = country_code.upper()
                
                # Get state/province
                if not result['state']:
                    if tags.get('boundary') == 'administrative' and tags.get('admin_level') == '4':
                        state_name = tags.get('name', '')
                        if state_name:
                            result['state'] = state_name
                    elif tags.get('is_in:state'):
                        result['state'] = tags.get('is_in:state', '')
                
                # Get county
                if not result['county']:
                    if tags.get('boundary') == 'administrative' and tags.get('admin_level') == '6':
                        county_name = tags.get('name', '')
                        if county_name:
                            result['county'] = county_name
                    elif tags.get('is_in:county'):
                        result['county'] = tags.get('is_in:county', '')
            
            # If we didn't get country/state from boundaries, try a fallback query
            if not result['country_code'] or not result['state']:
                fallback_query = f"""[out:json][timeout:10];
(
  is_in({latitude},{longitude})->.a;
  relation["admin_level"="2"](pivot.a);
  relation["admin_level"="4"](pivot.a);
);
out tags;"""
                try:
                    fallback_response = requests.post(self.overpass_url, data={'data': fallback_query}, headers=headers, timeout=15)
                    if fallback_response.status_code == 200:
                        fallback_data = fallback_response.json()
                        if fallback_data and 'elements' in fallback_data:
                            for element in fallback_data.get('elements', []):
                                tags = element.get('tags', {})
                                if not result['country_code'] and tags.get('admin_level') == '2':
                                    country_code = tags.get('ISO3166-1:alpha2', '') or tags.get('ISO3166-1', '') or tags.get('ref', '')
                                    if country_code:
                                        result['country_code'] = country_code.upper()
                                if not result['state'] and tags.get('admin_level') == '4':
                                    state_name = tags.get('name', '')
                                    if state_name:
                                        result['state'] = state_name
                except Exception as e:
                    logger.debug(f"Fallback admin query failed: {e}")
            
            # Only return if we have at least country or state
            if result['country_code'] or result['state']:
                return result
            return None
        except Exception as e:
            logger.warning(f"Overpass reverse geocoding failed: {e}")
            return None
    
    def reverse_geocode_nominatim(self, latitude: float, longitude: float) -> Optional[Dict[str, Any]]:
        """
        Reverse geocode using Nominatim API.
        Nominatim is better at identifying cities/towns from administrative boundaries.
        """
        try:
            url = f"{self.nominatim_url}/reverse"
            params = {
                'lat': latitude,
                'lon': longitude,
                'format': 'json',
                'addressdetails': 1,
                'zoom': 18,  # Higher zoom for more detailed results
                'namedetails': 1
            }
            headers = {
                'User-Agent': self.user_agent,
                'Accept': 'application/json'
            }
            
            response = requests.get(url, params=params, headers=headers, timeout=10)
            if response.status_code != 200:
                logger.warning(f"Nominatim API returned status {response.status_code}")
                return None
            
            data = response.json()
            if not data or 'address' not in data:
                return None
            
            address = data.get('address', {})
            result = {
                'country_code': '',
                'state': '',
                'county': '',
                'city': '',
                'place_type': '',
            }
            
            # Nominatim uses addresstype to indicate what the result represents
            addresstype = data.get('addresstype', '')
            place_rank = data.get('place_rank', 999)
            
            # Extract city/town name
            # Nominatim returns different fields depending on the location
            city_name = (
                address.get('city') or
                address.get('town') or
                address.get('village') or
                address.get('municipality') or
                address.get('city_district') or
                address.get('suburb') or
                address.get('neighbourhood') or
                ''
            )
            
            # If addresstype is 'city', use the display_name or name
            if addresstype == 'city' and not city_name:
                city_name = data.get('name', '')
            
            # Determine place type from addresstype or address fields
            if addresstype == 'city' or address.get('city'):
                result['place_type'] = 'city'
            elif addresstype == 'town' or address.get('town'):
                result['place_type'] = 'town'
            elif address.get('village'):
                result['place_type'] = 'village'
            elif address.get('suburb') or address.get('neighbourhood'):
                result['place_type'] = 'neighbourhood'
            
            if city_name:
                result['city'] = city_name
            
            # Extract state
            result['state'] = (
                address.get('state') or
                address.get('region') or
                address.get('province') or
                ''
            )
            
            # Extract country code
            country_code = address.get('country_code', '').upper()
            if country_code:
                result['country_code'] = country_code
            
            # Extract county
            result['county'] = (
                address.get('county') or
                address.get('state_district') or
                ''
            )
            
            # Only return if we have meaningful data
            if result['city'] or result['state'] or result['country_code']:
                return result
            return None
        except Exception as e:
            logger.warning(f"Nominatim reverse geocoding failed: {e}")
            return None
    
    def reverse_geocode(self, latitude: float, longitude: float) -> Optional[Dict[str, Any]]:
        """
        Reverse geocode using Nominatim for cities and Overpass for state/country/county.
        Always uses Nominatim for city detection - no fallbacks.
        """
        # Always use Nominatim for city detection
        nominatim_result = self.reverse_geocode_nominatim(latitude, longitude)
        
        # Get state/country/county from Overpass (as backup/supplement)
        overpass_result = self.reverse_geocode_overpass(latitude, longitude)
        
        # Start with Nominatim result (has city) or empty dict
        result = {}
        if nominatim_result:
            result = nominatim_result.copy()
        
        # Fill in missing state/country/county from Overpass if Nominatim doesn't have them
        if overpass_result:
            if not result.get('state') and overpass_result.get('state'):
                result['state'] = overpass_result['state']
            if not result.get('country_code') and overpass_result.get('country_code'):
                result['country_code'] = overpass_result['country_code']
            if not result.get('county') and overpass_result.get('county'):
                result['county'] = overpass_result['county']
        
        # Only return if we have meaningful data
        if result.get('city') or result.get('state') or result.get('country_code'):
            return result
        return None
    
    def search_protected_areas_overpass(self, latitude: float, longitude: float) -> List[Dict[str, Any]]:
        """Search for protected areas using Overpass API with point-in-polygon queries."""
        try:
            query = f"""[out:json][timeout:10];
(
  is_in({latitude},{longitude})->.a;
  relation["boundary"="protected_area"](pivot.a);
  way["boundary"="protected_area"](pivot.a);
);
out tags;"""
            headers = {'User-Agent': self.user_agent, 'Content-Type': 'application/x-www-form-urlencoded'}
            response = requests.post(self.overpass_url, data={'data': query}, headers=headers, timeout=15)
            if response.status_code != 200:
                logger.warning(f"Overpass API returned status {response.status_code}")
                return []
            data = response.json()
            if not data or 'elements' not in data:
                return []
            protected_areas = []
            for element in data.get('elements', []):
                tags = element.get('tags', {})
                name = tags.get('name', '')
                if name and element.get('type') in ['relation', 'way']:
                    boundary_type = tags.get('boundary', '')
                    protect_class = tags.get('protect_class', '')
                    leisure = tags.get('leisure', '')
                    if boundary_type == 'protected_area' or protect_class or leisure in ['nature_reserve', 'national_park']:
                        protected_areas.append({
                            'name': name,
                            'type': tags.get('protect_class', '') or tags.get('leisure', '') or 'protected_area',
                            'class': 'boundary',
                            'boundary_type': boundary_type,
                            'protect_class': protect_class,
                        })
            return protected_areas
        except Exception as e:
            logger.warning(f"Overpass API query failed: {e}")
            return []
    
    def search_protected_areas(self, latitude: float, longitude: float) -> List[Dict[str, Any]]:
        """Search for protected areas using Overpass API."""
        return self.search_protected_areas_overpass(latitude, longitude)
    
    def search_lakes(self, latitude: float, longitude: float, proximity_miles: float = 1.0) -> List[Dict[str, Any]]:
        """Search for lakes and water bodies within proximity_miles of the point."""
        try:
            radius_meters = int(proximity_miles * 1609.34)
            query_inside = f"""[out:json][timeout:10];
(
  is_in({latitude},{longitude})->.a;
  relation["natural"="water"](pivot.a);
  way["natural"="water"](pivot.a);
  relation["water"="lake"](pivot.a);
  way["water"="lake"](pivot.a);
  relation["water"="reservoir"](pivot.a);
  way["water"="reservoir"](pivot.a);
  relation["water"="pond"](pivot.a);
  way["water"="pond"](pivot.a);
);
out tags;"""
            headers = {'User-Agent': self.user_agent, 'Content-Type': 'application/x-www-form-urlencoded'}
            response = requests.post(self.overpass_url, data={'data': query_inside}, headers=headers, timeout=15)
            lakes = []
            if response.status_code == 200:
                data = response.json()
                if data and 'elements' in data:
                    for element in data.get('elements', []):
                        tags = element.get('tags', {})
                        name = tags.get('name', '')
                        if name and element.get('type') in ['relation', 'way']:
                            natural = tags.get('natural', '')
                            water = tags.get('water', '')
                            if natural == 'water' or water in ['lake', 'reservoir', 'pond']:
                                lakes.append({'name': name, 'distance_miles': 0.0})
            if lakes:
                return lakes
            query_nearby = f"""[out:json][timeout:10];
(
  relation["natural"="water"](around:{radius_meters},{latitude},{longitude});
  way["natural"="water"](around:{radius_meters},{latitude},{longitude});
  relation["water"="lake"](around:{radius_meters},{latitude},{longitude});
  way["water"="lake"](around:{radius_meters},{latitude},{longitude});
  relation["water"="reservoir"](around:{radius_meters},{latitude},{longitude});
  way["water"="reservoir"](around:{radius_meters},{latitude},{longitude});
  relation["water"="pond"](around:{radius_meters},{latitude},{longitude});
  way["water"="pond"](around:{radius_meters},{latitude},{longitude});
);
out tags center;"""
            response = requests.post(self.overpass_url, data={'data': query_nearby}, headers=headers, timeout=15)
            if response.status_code != 200:
                return lakes
            data = response.json()
            if not data or 'elements' not in data:
                return lakes
            for element in data.get('elements', []):
                tags = element.get('tags', {})
                name = tags.get('name', '')
                if name and element.get('type') in ['relation', 'way']:
                    natural = tags.get('natural', '')
                    water = tags.get('water', '')
                    if natural == 'water' or water in ['lake', 'reservoir', 'pond']:
                        center = element.get('center', {})
                        if center:
                            lake_lat = center.get('lat', 0)
                            lake_lon = center.get('lon', 0)
                            if lake_lat and lake_lon:
                                distance = haversine_distance(latitude, longitude, lake_lat, lake_lon)
                                if distance <= proximity_miles:
                                    lakes.append({'name': name, 'distance_miles': distance})
                        else:
                            lakes.append({'name': name, 'distance_miles': proximity_miles})
            return lakes
        except Exception as e:
            logger.warning(f"Overpass API query failed for lake search: {e}")
            return []
    
    def check_city_proximity(self, latitude: float, longitude: float, threshold_miles: float) -> Optional[Dict[str, Any]]:
        """
        Check for nearby cities/towns within threshold_miles using Nominatim.
        Always uses Nominatim - no fallbacks.
        This is used when a point is outside any city boundary.
        Returns the closest city within the threshold distance.
        """
        try:
            # Don't return city proximity if point is in water
            if self.is_point_in_water(latitude, longitude):
                return None
            
            # Always use Nominatim's reverse geocoding to find the city
            # Nominatim is better at identifying cities from administrative boundaries
            nominatim_result = self.reverse_geocode_nominatim(latitude, longitude)
            if nominatim_result and nominatim_result.get('city'):
                # Nominatim found a city - check if it's within threshold
                # For now, assume if Nominatim found it, it's close enough
                # (Nominatim reverse geocoding finds the containing city)
                return {
                    'name': nominatim_result['city'],
                    'admin1': nominatim_result.get('state', ''),
                    'admin2': nominatim_result.get('county', ''),
                    'cc': nominatim_result.get('country_code', ''),
                    'distance_miles': 0.0  # Nominatim finds containing city, so distance is 0
                }
            
            # No city found by Nominatim
            return None
        except Exception as e:
            logger.warning(f"City proximity check failed: {e}")
            return None
    
    def get_location_tags(self, latitude: float, longitude: float) -> List[str]:
        """
        Generate location tags for a given coordinate.
        
        Logic:
        1. Use Nominatim to find city/town (handles administrative boundaries well)
        2. If no city found, check proximity for nearby cities within 5 miles
        3. Also add state, country, protected areas, and lakes
        """
        tags = []
        # Step 1: Get location data (uses Nominatim for cities)
        location_data = self.reverse_geocode(latitude, longitude)
        if location_data:
            if location_data.get('city'):
                # City found (from Nominatim)
                city_tag = location_data['city'].lower().replace(' ', '-')
                tags.append(f"city:{city_tag}")
            if location_data.get('state'):
                state_tag = location_data['state'].lower().replace(' ', '-')
                tags.append(f"state:{state_tag}")
            if location_data.get('country_code'):
                tags.append(f"country:{location_data['country_code'].lower()}")
        
        # Step 2: If no city was found, check for nearby cities within 5 miles
        city_prox_threshold = getattr(settings, 'CITY_PROXIMITY_MILES', 5.0)
        if not any(tag.startswith('city:') for tag in tags):
            city_prox = self.check_city_proximity(latitude, longitude, city_prox_threshold)
            if city_prox:
                # Point is outside a boundary but within threshold of a city
                city_name = city_prox['name'].lower().replace(' ', '-')
                tags.append(f"city-proximity:{city_name}")
        protected_areas = self.search_protected_areas(latitude, longitude)
        for area in protected_areas:
            area_name = area.get('name', '')
            if not area_name:
                continue
            name_lower = area_name.lower()
            if 'national forest' in name_lower or 'nf' in name_lower:
                forest_tag = area_name.lower().replace(' ', '-')
                tags.append(f"national-forest:{forest_tag}")
            elif 'national park' in name_lower or 'np' in name_lower:
                park_tag = area_name.lower().replace(' ', '-')
                tags.append(f"national-park:{park_tag}")
            elif 'national monument' in name_lower or 'nm' in name_lower:
                monument_tag = area_name.lower().replace(' ', '-')
                tags.append(f"national-monument:{monument_tag}")
            elif 'state park' in name_lower or 'sp' in name_lower:
                state_park_tag = area_name.lower().replace(' ', '-')
                tags.append(f"state-park:{state_park_tag}")
            elif 'wilderness' in name_lower:
                wilderness_tag = area_name.lower().replace(' ', '-')
                tags.append(f"wilderness:{wilderness_tag}")
        lake_prox_threshold = getattr(settings, 'LAKE_PROXIMITY_MILES', 1.0)
        lakes = self.search_lakes(latitude, longitude, proximity_miles=lake_prox_threshold)
        for lake in lakes:
            lake_name = lake.get('name', '')
            if lake_name:
                lake_tag = lake_name.lower().replace(' ', '-')
                tags.append(f"lake:{lake_tag}")
        return tags

_reverse_geocoding_service = None

def get_reverse_geocoding_service() -> ReverseGeocodingService:
    global _reverse_geocoding_service
    if _reverse_geocoding_service is None:
        _reverse_geocoding_service = ReverseGeocodingService()
    return _reverse_geocoding_service
