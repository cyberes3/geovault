"""
Reverse geocoding service using Overpass API and reverse-geocoder.
"""
import logging
import math
from typing import Optional, Dict, Any, List
import requests
import reverse_geocoder as rg
from django.conf import settings

logger = logging.getLogger(__name__)

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
    def __init__(self, overpass_url: Optional[str] = None):
        self.overpass_url = overpass_url or getattr(settings, 'OVERPASS_API_URL', 'https://overpass-api.de/api/interpreter')
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
        """Reverse geocode using Overpass API to find actual administrative boundaries."""
        try:
            # First check if point is in water - if so, don't return city data
            if self.is_point_in_water(latitude, longitude):
                # Still get state and country for water bodies
                query = f"""[out:json][timeout:10];
(
  is_in({latitude},{longitude})->.a;
  relation["admin_level"="2"](pivot.a);  // Country
  relation["admin_level"="4"](pivot.a);  // State/Province
);
out tags;"""
            else:
                # Find cities, towns, villages, etc. that contain the point
                query = f"""[out:json][timeout:10];
(
  is_in({latitude},{longitude})->.a;
  relation["place"~"^(city|town|village|hamlet|suburb|neighbourhood)$"](pivot.a);
  way["place"~"^(city|town|village|hamlet|suburb|neighbourhood)$"](pivot.a);
  node["place"~"^(city|town|village|hamlet|suburb|neighbourhood)$"](pivot.a);
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
                'city': '',
            }
            
            # Process results - prioritize place tags, then admin levels
            for element in data.get('elements', []):
                tags = element.get('tags', {})
                place_type = tags.get('place', '')
                
                # Get city/town/village name
                if place_type in ['city', 'town', 'village', 'hamlet', 'suburb', 'neighbourhood']:
                    name = tags.get('name', '')
                    if name and not result['city']:
                        result['city'] = name
                
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
    
    def reverse_geocode(self, latitude: float, longitude: float) -> Optional[Dict[str, Any]]:
        """Reverse geocode using Overpass API to find actual administrative boundaries."""
        return self.reverse_geocode_overpass(latitude, longitude)
    
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
        """Check for nearby cities, but only if point is not in water."""
        try:
            # Don't return city proximity if point is in water
            if self.is_point_in_water(latitude, longitude):
                return None
            
            # Use Overpass to find nearby cities
            radius_meters = int(threshold_miles * 1609.34)
            query = f"""[out:json][timeout:10];
(
  node["place"~"^(city|town|village)$"](around:{radius_meters},{latitude},{longitude});
  way["place"~"^(city|town|village)$"](around:{radius_meters},{latitude},{longitude});
  relation["place"~"^(city|town|village)$"](around:{radius_meters},{latitude},{longitude});
);
out center tags;"""
            headers = {'User-Agent': self.user_agent, 'Content-Type': 'application/x-www-form-urlencoded'}
            response = requests.post(self.overpass_url, data={'data': query}, headers=headers, timeout=15)
            if response.status_code == 200:
                data = response.json()
                if data and 'elements' in data:
                    closest_city = None
                    closest_distance = float('inf')
                    
                    for element in data.get('elements', []):
                        tags = element.get('tags', {})
                        name = tags.get('name', '')
                        if not name:
                            continue
                        
                        # Get coordinates
                        if element.get('type') == 'node':
                            city_lat = element.get('lat', 0)
                            city_lon = element.get('lon', 0)
                        else:
                            center = element.get('center', {})
                            city_lat = center.get('lat', 0) if center else 0
                            city_lon = center.get('lon', 0) if center else 0
                        
                        if city_lat and city_lon:
                            distance = haversine_distance(latitude, longitude, city_lat, city_lon)
                            if distance < closest_distance and distance <= threshold_miles:
                                closest_distance = distance
                                closest_city = {
                                    'name': name,
                                    'admin1': tags.get('is_in:state', '') or tags.get('addr:state', ''),
                                    'admin2': tags.get('is_in:county', '') or tags.get('addr:county', ''),
                                    'cc': tags.get('ISO3166-1:alpha2', '').upper() if tags.get('ISO3166-1:alpha2') else '',
                                    'distance_miles': distance
                                }
                    
                    return closest_city
            
            # Fallback to reverse-geocoder if Overpass fails
            results = rg.search((latitude, longitude))
            if not results:
                return None
            city_info = results[0]
            city_lat = float(city_info.get('lat', 0))
            city_lon = float(city_info.get('lon', 0))
            distance = haversine_distance(latitude, longitude, city_lat, city_lon)
            if distance <= threshold_miles:
                return {'name': city_info.get('name', ''), 'admin1': city_info.get('admin1', ''), 'admin2': city_info.get('admin2', ''), 'cc': city_info.get('cc', ''), 'distance_miles': distance}
            return None
        except Exception as e:
            logger.warning(f"City proximity check failed: {e}")
            return None
    
    def get_location_tags(self, latitude: float, longitude: float) -> List[str]:
        tags = []
        location_data = self.reverse_geocode(latitude, longitude)
        if location_data:
            if location_data.get('city'):
                city_tag = location_data['city'].lower().replace(' ', '-')
                tags.append(f"city:{city_tag}")
            if location_data.get('state'):
                state_tag = location_data['state'].lower().replace(' ', '-')
                tags.append(f"state:{state_tag}")
            if location_data.get('country_code'):
                tags.append(f"country:{location_data['country_code'].lower()}")
        city_prox_threshold = getattr(settings, 'CITY_PROXIMITY_MILES', 5.0)
        city_prox = self.check_city_proximity(latitude, longitude, city_prox_threshold)
        if city_prox and not any(tag.startswith('city:') for tag in tags):
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
