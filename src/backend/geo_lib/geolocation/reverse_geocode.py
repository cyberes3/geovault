"""
Reverse geocoding service using Overpass API.
Generates location tags (city, state, county, country, protected areas) for coordinates.
"""
import json
import math
import time
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock
from dataclasses import dataclass
from datetime import datetime

import requests
from django.core.cache import caches
from django.conf import settings

from geo_lib.logging.console import get_geocode_logger

logger = get_geocode_logger()

# Cache TTL: 30 days in seconds
GEOCODING_CACHE_TTL = 30 * 24 * 60 * 60

# Load ski resort database
_SKI_RESORTS = None
_SKI_RESORTS_LOCK = Lock()


def _get_geocoding_cache():
    """
    Get the geocoding cache instance.
    Uses a separate Redis DB that persists across restarts.
    Falls back to default cache if geocoding cache is not configured.
    """
    try:
        return caches['geocoding']
    except Exception:
        # Fallback to default cache if geocoding cache not configured
        return caches['default']


@dataclass
class GeocodingLogMessage:
    """Log message from geocoding operations."""
    timestamp: datetime
    message: str
    level: str  # 'INFO', 'WARNING', 'ERROR'
    source: str  # 'Reverse Geocoding'


def _get_name_from_tags(tags: Dict[str, Any]) -> Optional[str]:
    """
    Get name from OSM tags, preferring English names for consistency.
    
    With Unicode database support, we can accept names in any language,
    but prefer English when available for better user experience.
    
    Args:
        tags: OSM element tags dictionary
    
    Returns:
        Name string or None
    """
    # Prefer English name for consistency across international locations
    name = tags.get('name:en')
    if name:
        return name
    
    # Fall back to international name if available
    name = tags.get('int_name')
    if name:
        return name
    
    # Fall back to default name (may be in local language)
    name = tags.get('name')
    return name

def load_ski_resorts() -> List[Dict[str, Any]]:
    """Load ski resort database from JSON file with thread-safe initialization."""
    global _SKI_RESORTS
    
    # Fast path: if already loaded, return immediately without acquiring lock
    if _SKI_RESORTS is not None:
        return _SKI_RESORTS
    
    # Slow path: acquire lock and load data (only first thread will do this)
    with _SKI_RESORTS_LOCK:
        # Double-check after acquiring lock (another thread may have loaded it)
        if _SKI_RESORTS is None:
            data_dir = Path(__file__).parent.parent / 'data'
            ski_resorts_file = data_dir / 'ski_resorts.json'
            with open(ski_resorts_file, 'r', encoding='utf-8') as f:
                data = json.load(f)
                _SKI_RESORTS = data.get('ski_resorts', [])
                logger.info(f"Loaded {len(_SKI_RESORTS)} ski resorts from database")
    
    return _SKI_RESORTS

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


def _get_cache_key(latitude: float, longitude: float, prefix: str = "geocode") -> str:
    """
    Generate cache key for coordinate (rounded to ~111m precision).
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
        prefix: Cache key prefix
    
    Returns:
        Cache key string
    """
    # Round to 3 decimal places (~111m precision)
    lat_rounded = round(latitude, 3)
    lon_rounded = round(longitude, 3)
    return f"{prefix}:{lat_rounded},{lon_rounded}"


class ReverseGeocodingService:
    """Reverse geocoding service using Overpass API."""
    
    def __init__(self):
        """Initialize the reverse geocoding service."""
        self.api_url = settings.OVERPASS_API_URL
        self.timeout = settings.OVERPASS_API_TIMEOUT
        self.city_proximity_miles = settings.CITY_PROXIMITY_MILES
        self.lake_proximity_miles = settings.LAKE_PROXIMITY_MILES
    
    def _log_overpass_failure(self, response: requests.Response, error_type: str, additional_info: str = ""):
        """
        Log comprehensive information about an Overpass API failure.
        
        Args:
            response: The requests Response object
            error_type: Type of error (e.g., "Invalid JSON", "Empty Response", "Rate Limited")
            additional_info: Additional error information to log
        """
        status_code = response.status_code
        content_type = response.headers.get('content-type', 'unknown')
        content_length = len(response.content) if response.content else 0
        
        # Get response preview (truncated to 500 chars)
        content_preview = ""
        if response.text:
            content_preview = response.text[:500]
            # Replace newlines with escaped version for single-line logging
            content_preview = content_preview.replace('\n', '\\n').replace('\r', '\\r')
        
        # Build complete error message on one line
        error_parts = [
            f"Overpass API Failure: {error_type}",
            f"Status={status_code}",
            f"Content-Type={content_type}",
            f"Length={content_length}bytes",
            f"URL={self.api_url}"
        ]
        
        if additional_info:
            error_parts.append(f"Details=[{additional_info}]")
        
        if content_preview:
            error_parts.append(f"Preview=[{content_preview}]")
        else:
            error_parts.append("Response=(empty)")
        
        # Log everything on one line
        logger.error(" | ".join(error_parts))
    
    def _query_overpass(self, query: str, max_retries: int = 3) -> Optional[Dict[str, Any]]:
        """
        Query Overpass API with error handling and retry logic.
        
        Args:
            query: Overpass QL query string
            max_retries: Maximum number of retry attempts
        
        Returns:
            JSON response dict or None on failure
        """
        for attempt in range(max_retries):
            try:
                response = requests.post(
                    self.api_url,
                    data=query,
                    timeout=self.timeout,
                    headers={'Content-Type': 'text/plain; charset=utf-8'}
                )
                
                if response.status_code == 200:
                    # Check if response has content before trying to parse JSON
                    if not response.content or len(response.content) == 0:
                        self._log_overpass_failure(response, "Empty Response", "API returned 200 OK but with no content")
                        return None
                    
                    # Check if response is HTML/XML instead of JSON
                    content_type = response.headers.get('content-type', '').lower()
                    if 'html' in content_type or 'xml' in content_type:
                        # Server returned an error page instead of JSON
                        self._log_overpass_failure(response, "HTML/XML Error Page", f"Expected JSON but got {content_type}")
                        return None
                    
                    try:
                        return response.json()
                    except json.JSONDecodeError as json_err:
                        # Log the response content to help debug
                        self._log_overpass_failure(response, "Invalid JSON", str(json_err))
                        return None
                        
                elif response.status_code == 429:  # Rate limited
                    self._log_overpass_failure(response, "Rate Limited", f"Attempt {attempt + 1}/{max_retries}, waiting 60s")
                    time.sleep(60)  # Wait 1 minute
                    continue
                elif response.status_code == 504:  # Gateway timeout
                    self._log_overpass_failure(response, "Gateway Timeout", f"Attempt {attempt + 1}/{max_retries}, waiting 5s")
                    time.sleep(5)
                    continue
                else:
                    self._log_overpass_failure(response, f"HTTP {response.status_code}", "Unexpected status code")
                    return None
                    
            except requests.exceptions.Timeout:
                logger.warning(f"Overpass request timeout, attempt {attempt + 1}/{max_retries}")
                if attempt < max_retries - 1:
                    time.sleep(2 ** attempt)  # Exponential backoff
            except json.JSONDecodeError:
                # Already handled above, but catch it here in case it happens elsewhere
                pass
            except Exception as e:
                logger.error(f"Overpass query failed: {e}")
                return None
        
        return None
    
    def _get_admin_hierarchy(self, latitude: float, longitude: float) -> Dict[str, Optional[str]]:
        """
        Get administrative hierarchy (country, state, county, city) for a coordinate.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
        
        Returns:
            Dict with 'country', 'state', 'county', 'city' keys
        """
        # Check cache first
        cache_key = _get_cache_key(latitude, longitude, prefix="geocode:admin")
        geocoding_cache = _get_geocoding_cache()
        cached = geocoding_cache.get(cache_key)
        if cached is not None:
            return cached
        
        query = f"""
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["admin_level"="2"];
  area.a["admin_level"="4"];
  area.a["admin_level"="6"];
  area.a["admin_level"="8"];
);
out tags;
"""
        
        result = {
            'country': None,
            'state': None,
            'county': None,
            'city': None
        }
        
        response = self._query_overpass(query)
        if response:
            for element in response.get('elements', []):
                tags = element.get('tags', {})
                admin_level = tags.get('admin_level')
                name = _get_name_from_tags(tags)
                boundary = tags.get('boundary', '')
                
                if not name:
                    continue
                
                # Filter out non-governmental administrative boundaries
                # (e.g., religious dioceses, school districts)
                if boundary != 'administrative':
                    continue
                
                if admin_level == '2':
                    result['country'] = name
                elif admin_level == '4':
                    result['state'] = name
                elif admin_level == '6':
                    result['county'] = name
                elif admin_level == '8':
                    result['city'] = name
            
            # Only cache on successful API response
            geocoding_cache.set(cache_key, result, GEOCODING_CACHE_TTL)
        
        return result
    
    def _find_nearby_cities(self, latitude: float, longitude: float, threshold_miles: float) -> List[Dict[str, Any]]:
        """
        Find cities/towns within threshold_miles of a point.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            threshold_miles: Search radius in miles
        
        Returns:
            List of dicts with 'name', 'distance_miles', 'place_type' keys, sorted by distance
        """
        # Check cache first
        cache_key = _get_cache_key(latitude, longitude, prefix=f"geocode:cities:{threshold_miles}")
        geocoding_cache = _get_geocoding_cache()
        cached = geocoding_cache.get(cache_key)
        if cached is not None:
            return cached
        
        # Convert miles to meters (1 mile = 1609.34 meters)
        radius_meters = int(threshold_miles * 1609.34)
        
        query = f"""
[out:json];
(
  node["place"~"town|city|village"](around:{radius_meters},{latitude},{longitude});
);
out center;
"""
        
        cities = []
        response = self._query_overpass(query)
        if response:
            for element in response.get('elements', []):
                tags = element.get('tags', {})
                name = _get_name_from_tags(tags)
                lat = element.get('lat')
                lon = element.get('lon')
                
                if name and lat is not None and lon is not None:
                    distance = haversine_distance(latitude, longitude, lat, lon)
                    if distance <= threshold_miles:
                        cities.append({
                            'name': name,
                            'distance_miles': distance,
                            'place_type': tags.get('place', '')
                        })
            
            # Sort by distance
            cities.sort(key=lambda x: x['distance_miles'])
            
            # Only cache on successful API response
            geocoding_cache.set(cache_key, cities, GEOCODING_CACHE_TTL)
        else:
            # Sort by distance even if no response (for consistency)
            cities.sort(key=lambda x: x['distance_miles'])
        
        return cities
    
    def _get_protected_areas(self, latitude: float, longitude: float) -> List[Dict[str, str]]:
        """
        Get all protected areas containing a point.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
        
        Returns:
            List of protected area dicts with name and classification info
        """
        # Check cache first
        cache_key = _get_cache_key(latitude, longitude, prefix="geocode:protected")
        geocoding_cache = _get_geocoding_cache()
        cached = geocoding_cache.get(cache_key)
        if cached is not None:
            return cached
        
        query = f"""
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
  area.a["boundary"="national_park"];
);
out tags;
"""
        
        protected_areas = []
        response = self._query_overpass(query)
        if response:
            for element in response.get('elements', []):
                tags = element.get('tags', {})
                name = _get_name_from_tags(tags)
                
                if name:
                    area_info = {
                        'name': name,
                        'protection_title': tags.get('protection_title', ''),
                        'protect_class': tags.get('protect_class', ''),
                        'designation': tags.get('designation', ''),
                        'operator': tags.get('operator', ''),
                        'leisure': tags.get('leisure', ''),
                        'boundary': tags.get('boundary', '')
                    }
                    protected_areas.append(area_info)
            
            # Only cache on successful API response
            geocoding_cache.set(cache_key, protected_areas, GEOCODING_CACHE_TTL)
        
        return protected_areas
    
    def _search_nearby_lakes(self, latitude: float, longitude: float, proximity_miles: float = 1.0) -> List[Dict[str, Any]]:
        """
        Search for lakes and water bodies within proximity_miles of a point.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            proximity_miles: Distance threshold in miles
        
        Returns:
            List of lake dicts with name and distance
        """
        # Check cache first
        cache_key = _get_cache_key(latitude, longitude, prefix=f"geocode:lakes:{proximity_miles}")
        geocoding_cache = _get_geocoding_cache()
        cached = geocoding_cache.get(cache_key)
        if cached is not None:
            return cached
        
        # Convert miles to meters
        radius_meters = int(proximity_miles * 1609.34)
        
        query = f"""
[out:json];
(
  way["natural"="water"]["name"](around:{radius_meters},{latitude},{longitude});
  relation["natural"="water"]["name"](around:{radius_meters},{latitude},{longitude});
  way["water"="lake"]["name"](around:{radius_meters},{latitude},{longitude});
  relation["water"="lake"]["name"](around:{radius_meters},{latitude},{longitude});
);
out tags center;
"""
        
        lakes = []
        response = self._query_overpass(query)
        if response:
            for element in response.get('elements', []):
                tags = element.get('tags', {})
                name = _get_name_from_tags(tags)
                water_type = tags.get('water', '')
                
                # Only include lakes, not rivers/streams
                if name and water_type in ['lake', 'reservoir', 'pond', '']:
                    # Get center coordinates
                    lat = element.get('lat')
                    lon = element.get('lon')
                    center = element.get('center', {})
                    if not lat:
                        lat = center.get('lat')
                        lon = center.get('lon')
                    
                    if lat and lon:
                        distance = haversine_distance(latitude, longitude, lat, lon)
                        if distance <= proximity_miles:
                            lakes.append({
                                'name': name,
                                'distance_miles': distance,
                                'water_type': water_type or 'water'
                            })
            
            # Sort by distance
            lakes.sort(key=lambda x: x['distance_miles'])
            
            # Only cache on successful API response
            geocoding_cache.set(cache_key, lakes, GEOCODING_CACHE_TTL)
        else:
            # Sort by distance even if no response (for consistency)
            lakes.sort(key=lambda x: x['distance_miles'])
        
        return lakes
    
    def _search_nearby_ski_resorts(self, latitude: float, longitude: float, proximity_miles: float = 2.0) -> List[Dict[str, Any]]:
        """
        Search for ski resorts within proximity_miles of a point using local database.
        
        Uses a pre-compiled database of major ski resorts with bounding boxes.
        First checks if point is inside any resort bbox, then finds nearest resorts.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            proximity_miles: Distance threshold in miles (not used for bbox check)
        
        Returns:
            List of ski resort dicts with name and distance
        """
        # Check cache first
        cache_key = _get_cache_key(latitude, longitude, prefix="geocode:ski")
        geocoding_cache = _get_geocoding_cache()
        cached = geocoding_cache.get(cache_key)
        if cached is not None:
            return cached
        
        ski_resorts_data = load_ski_resorts()
        matching_resorts = []
        
        # Check if point is inside any resort bbox
        for resort in ski_resorts_data:
            bbox = resort.get('bbox', {})
            if not bbox:
                continue
            
            # Check if point is inside bbox
            if (bbox['min_lat'] <= latitude <= bbox['max_lat'] and
                bbox['min_lon'] <= longitude <= bbox['max_lon']):
                # Point is inside resort - distance is 0
                matching_resorts.append({
                    'name': resort['name'],
                    'distance_miles': 0.0,
                    'country': resort.get('country', ''),
                    'state': resort.get('state', '')
                })
        
        # If not inside any resort, find nearby ones
        if not matching_resorts:
            for resort in ski_resorts_data:
                bbox = resort.get('bbox', {})
                if not bbox:
                    continue
                
                # Calculate distance to bbox center
                center_lat = (bbox['min_lat'] + bbox['max_lat']) / 2
                center_lon = (bbox['min_lon'] + bbox['max_lon']) / 2
                distance = haversine_distance(latitude, longitude, center_lat, center_lon)
                
                if distance <= proximity_miles:
                    matching_resorts.append({
                        'name': resort['name'],
                        'distance_miles': distance,
                        'country': resort.get('country', ''),
                        'state': resort.get('state', '')
                    })
        
        # Sort by distance
        matching_resorts.sort(key=lambda x: x['distance_miles'])
        
        # Cache for 30 days
        geocoding_cache.set(cache_key, matching_resorts, GEOCODING_CACHE_TTL)
        return matching_resorts

    def reverse_geocode(self, latitude: float, longitude: float, import_log=None) -> Optional[Dict[str, Any]]:
        """
        Reverse geocode a coordinate to get comprehensive location information.
        Uses parallel API calls to speed up the process.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            import_log: Optional ImportLog for database logging
        
        Returns:
            Dict with location information or None on failure
        """
        try:
            # Run the two main API calls in parallel
            # (admin hierarchy and protected areas are independent)
            with ThreadPoolExecutor(max_workers=2) as executor:
                # Submit both tasks
                admin_future = executor.submit(self._get_admin_hierarchy, latitude, longitude)
                protected_future = executor.submit(self._get_protected_areas, latitude, longitude)
                
                # Wait for results
                admin_info = admin_future.result()
                protected_areas = protected_future.result()
            
            # Check for nearby cities if not inside one (sequential, depends on admin_info)
            city_found = admin_info.get('city') is not None
            nearby_city = None
            if not city_found:
                nearby_cities = self._find_nearby_cities(latitude, longitude, self.city_proximity_miles)
                if nearby_cities:
                    nearby_city = nearby_cities[0]['name']
            
            return {
                'country': admin_info.get('country'),
                'state': admin_info.get('state'),
                'county': admin_info.get('county'),
                'city': admin_info.get('city') or nearby_city,
                'protected_areas': protected_areas
            }
        except Exception as e:
            error_msg = f"Reverse geocoding failed for ({latitude}, {longitude}): {e}"
            logger.error(error_msg)
            if import_log:
                from geo_lib.processing.logging import DatabaseLogLevel
                import_log.add(
                    error_msg,
                    "Reverse Geocoding",
                    DatabaseLogLevel.ERROR
                )
            return None
    
    def search_protected_areas(self, latitude: float, longitude: float, import_log=None) -> List[Dict[str, Any]]:
        """
        Search for protected areas at a given coordinate.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            import_log: Optional ImportLog for database logging
        
        Returns:
            List of protected area dicts
        """
        try:
            return self._get_protected_areas(latitude, longitude)
        except Exception as e:
            error_msg = f"Protected area search failed for ({latitude}, {longitude}): {e}"
            logger.error(error_msg)
            if import_log:
                from geo_lib.processing.logging import DatabaseLogLevel
                import_log.add(
                    error_msg,
                    "Reverse Geocoding",
                    DatabaseLogLevel.ERROR
                )
            return []
    
    def check_city_proximity(self, latitude: float, longitude: float, threshold_miles: float, import_log=None) -> Optional[Dict[str, Any]]:
        """
        Check for nearby cities/towns within threshold_miles.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            threshold_miles: Distance threshold in miles
            import_log: Optional ImportLog for database logging
        
        Returns:
            Dict with nearest city info or None if no city within threshold
        """
        try:
            nearby_cities = self._find_nearby_cities(latitude, longitude, threshold_miles)
            if nearby_cities:
                return nearby_cities[0]  # Return closest city
            return None
        except Exception as e:
            error_msg = f"City proximity check failed for ({latitude}, {longitude}): {e}"
            logger.error(error_msg)
            if import_log:
                from geo_lib.processing.logging import DatabaseLogLevel
                import_log.add(
                    error_msg,
                    "Reverse Geocoding",
                    DatabaseLogLevel.ERROR
                )
            return None
    
    def search_lakes(self, latitude: float, longitude: float, proximity_miles: float = 1.0, import_log=None) -> List[Dict[str, Any]]:
        """
        Search for lakes and water bodies within proximity_miles of the point.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            proximity_miles: Distance threshold in miles
            import_log: Optional ImportLog for database logging
        
        Returns:
            List of lake dicts with name and distance
        """
        try:
            return self._search_nearby_lakes(latitude, longitude, proximity_miles)
        except Exception as e:
            error_msg = f"Lake search failed for ({latitude}, {longitude}): {e}"
            logger.error(error_msg)
            if import_log:
                from geo_lib.processing.logging import DatabaseLogLevel
                import_log.add(
                    error_msg,
                    "Reverse Geocoding",
                    DatabaseLogLevel.ERROR
                )
            return []

    def get_location_tags(self, latitude: float, longitude: float) -> Tuple[List[str], List[GeocodingLogMessage]]:
        """
        Generate comprehensive location tags for a coordinate.
        Uses parallel API calls to speed up the process.
        
        Combines administrative hierarchy, city proximity search, and protected areas
        to generate tags in format: city:Name, state:Name, country:Name, etc.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
        
        Returns:
            Tuple of (tags list, log messages list)
        """
        tags = []
        log_messages = []
        
        try:
            # Step 1: Run independent queries in parallel
            # (admin hierarchy, protected areas, and lakes are all independent)
            with ThreadPoolExecutor(max_workers=3) as executor:
                # Submit all independent tasks
                admin_future = executor.submit(self._get_admin_hierarchy, latitude, longitude)
                protected_future = executor.submit(self._get_protected_areas, latitude, longitude)
                lakes_future = executor.submit(self._search_nearby_lakes, latitude, longitude, self.lake_proximity_miles)
                
                # Wait for results
                admin_info = admin_future.result()
                protected_areas = protected_future.result()
                nearby_lakes = lakes_future.result()
            
            # Check if we got any location data at all (indicates API failures)
            has_any_data = (
                admin_info.get('country') or admin_info.get('state') or 
                admin_info.get('county') or admin_info.get('city') or
                protected_areas or nearby_lakes
            )
            
            if not has_any_data:
                # No data returned from any query - likely API failures
                error_msg = f"Reverse geocoding returned no data for coordinates ({latitude}, {longitude}) - possible API failures (check console logs)"
                logger.warning(error_msg)
                log_messages.append(GeocodingLogMessage(
                    timestamp=datetime.now(),
                    message=error_msg,
                    level='WARNING',
                    source='Reverse Geocoding'
                ))
            
            # Add administrative tags
            if admin_info['country']:
                tags.append(f"country:{admin_info['country']}")
            if admin_info['state']:
                tags.append(f"state:{admin_info['state']}")
            if admin_info['county']:
                tags.append(f"county:{admin_info['county']}")
            
            city_found = False
            if admin_info['city']:
                tags.append(f"city:{admin_info['city']}")
                city_found = True
            
            # Step 2: If no city found, search for nearby cities (sequential, depends on admin_info)
            if not city_found:
                nearby_cities = self._find_nearby_cities(latitude, longitude, self.city_proximity_miles)
                if nearby_cities:
                    # Use closest city
                    closest_city = nearby_cities[0]
                    tags.append(f"city:{closest_city['name']}")
                    city_found = True
            
            # Step 3: Process protected areas
            protected_area_tags = set()  # Use set to prevent duplicates
            
            for area in protected_areas:
                protection_title = area.get('protection_title', '').lower()
                designation = area.get('designation', '').lower()
                operator = area.get('operator', '').lower()
                boundary = area.get('boundary', '').lower()
                name = area.get('name')
                
                if not name:
                    continue
                
                # Determine area type based on protection_title, designation, operator, and boundary
                # Check in priority order (most specific first)
                if 'national forest' in protection_title:
                    protected_area_tags.add(f"national-forest:{name}")
                elif 'wilderness' in protection_title or 'wilderness' in designation:
                    protected_area_tags.add(f"wilderness:{name}")
                elif 'national park' in protection_title or 'national park' in designation or 'national park' in boundary:
                    protected_area_tags.add(f"national-park:{name}")
                elif 'national monument' in protection_title or 'national monument' in designation:
                    protected_area_tags.add(f"national-monument:{name}")
                elif 'national wildlife refuge' in protection_title or 'wildlife refuge' in protection_title:
                    protected_area_tags.add(f"national-wildlife-refuge:{name}")
                elif 'national recreation area' in protection_title or 'national recreation area' in designation:
                    protected_area_tags.add(f"national-recreation-area:{name}")
                elif 'national historic' in protection_title or 'national historic' in designation:
                    protected_area_tags.add(f"national-historic-site:{name}")
                elif 'national seashore' in protection_title or 'national seashore' in designation:
                    protected_area_tags.add(f"national-seashore:{name}")
                elif 'national lakeshore' in protection_title or 'national lakeshore' in designation:
                    protected_area_tags.add(f"national-lakeshore:{name}")
                elif 'state park' in protection_title or 'state park' in designation or 'state park' in operator:
                    protected_area_tags.add(f"state-park:{name}")
                # Additional protected area types can be added here
            
            # Add protected area tags (sorted for consistency)
            tags.extend(sorted(protected_area_tags))
            
            # Step 4: Add lake tags (already fetched in parallel)
            lake_tags = set()
            for lake in nearby_lakes[:3]:  # Limit to 3 closest lakes
                lake_tags.add(f"lake:{lake['name']}")
            tags.extend(sorted(lake_tags))
            
            # Step 5: Search for nearby ski resorts (within 1 mile, limited by OSM data)
            # Note: Ski resort detection is limited in OSM - many resorts lack proper tagging
            nearby_ski_resorts = self._search_nearby_ski_resorts(latitude, longitude, 1.0)
            ski_tags = set()
            for resort in nearby_ski_resorts[:2]:  # Limit to 2 closest resorts
                ski_tags.add(f"ski-resort:{resort['name']}")
            tags.extend(sorted(ski_tags))
            
            return tags, log_messages
            
        except Exception as e:
            error_msg = f"Error generating location tags for ({latitude}, {longitude}): {e}"
            logger.error(error_msg)
            log_messages.append(GeocodingLogMessage(
                timestamp=datetime.now(),
                message=error_msg,
                level='ERROR',
                source='Reverse Geocoding'
            ))
            return [], log_messages


_reverse_geocoding_service = None


def get_reverse_geocoding_service() -> ReverseGeocodingService:
    """Get or create the singleton reverse geocoding service instance."""
    global _reverse_geocoding_service
    if _reverse_geocoding_service is None:
        _reverse_geocoding_service = ReverseGeocodingService()
    return _reverse_geocoding_service
