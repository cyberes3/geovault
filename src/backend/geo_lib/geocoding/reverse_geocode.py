"""
Reverse geocoding service using Overpass API with intelligent batching and caching.

This module provides efficient reverse geocoding that:
- Deduplicates coordinates automatically (~111m precision)
- Caches results for 30 days (persistent across restarts)
- Batches API calls to minimize Overpass API load
- Generates comprehensive location tags (city, state, country, protected areas, lakes, ski resorts)

Public API:
    get_reverse_geocoding_service() -> ReverseGeocodingService
        Get the singleton service instance
    
    ReverseGeocodingService.batch_geocode_coordinates(coordinates)
        Main entry point: Batch geocode multiple coordinates
        
    ReverseGeocodingService.get_location_tags(lat, lon)
        Internal: Generate tags for a single coordinate

Usage:
    from geo_lib.geocoding.reverse_geocode import get_reverse_geocoding_service
    
    service = get_reverse_geocoding_service()
    results = service.batch_geocode_coordinates([(lat1, lon1), (lat2, lon2)])
"""
import json
import time
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple
from concurrent.futures import ThreadPoolExecutor
from threading import Lock
from dataclasses import dataclass
from datetime import datetime

import requests
from django.core.cache import caches
from django.conf import settings

from geo_lib.logging.console import get_tagged_logger
from geo_lib.spatial.haversine import haversine_distance_miles

logger = get_tagged_logger('geocode')

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


def _round_coordinate(latitude: float, longitude: float) -> Tuple[float, float]:
    """
    Round coordinates to cache precision (~111m).
    
    Args:
        latitude: Latitude coordinate
        longitude: Longitude coordinate
    
    Returns:
        Tuple of (rounded_lat, rounded_lon)
    """
    return round(latitude, 3), round(longitude, 3)


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
    lat_rounded, lon_rounded = _round_coordinate(latitude, longitude)
    return f"{prefix}:{lat_rounded},{lon_rounded}"


class ReverseGeocodingService:
    """
    Reverse geocoding service using Overpass API with intelligent batching and caching.
    
    Architecture:
    - Primary API: batch_geocode_coordinates() - Batch process multiple coordinates
    - Internal: _get_from_cache_or_fetch() - Cache management
    - Implementation: get_location_tags() - Core geocoding logic
    
    Features:
    - Automatic coordinate deduplication (~111m precision)
    - Multi-level caching (per-query and top-level tag cache)
    - Parallel API calls for independent queries
    - Thread-safe operation
    
    Usage:
        >>> service = get_reverse_geocoding_service()
        >>> # Batch processing (preferred)
        >>> coords = [(lat1, lon1), (lat2, lon2), ...]
        >>> results = service.batch_geocode_coordinates(coords)
        >>> 
        >>> # Single coordinate (uses batch internally)
        >>> tags, logs = service.get_location_tags(lat, lon)
    """
    
    def __init__(self):
        """Initialize the reverse geocoding service."""
        self.api_url = settings.OVERPASS_API_URL
        self.timeout = settings.OVERPASS_API_TIMEOUT
        self.city_proximity_miles = settings.CITY_PROXIMITY_MILES
        self.lake_proximity_miles = settings.LAKE_PROXIMITY_MILES
    
    def _log_overpass_failure(self, response: requests.Response, error_type: str, additional_info: str = "", latitude: Optional[float] = None, longitude: Optional[float] = None):
        """
        Log comprehensive information about an Overpass API failure.
        
        Args:
            response: The requests Response object
            error_type: Type of error (e.g., "Invalid JSON", "Empty Response", "Rate Limited")
            additional_info: Additional error information to log
            latitude: Optional latitude coordinate being geocoded
            longitude: Optional longitude coordinate being geocoded
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
        
        # Add coordinates if available
        if latitude is not None and longitude is not None:
            error_parts.append(f"Coordinates=({latitude},{longitude})")
        
        if additional_info:
            error_parts.append(f"Details=[{additional_info}]")
        
        if content_preview:
            error_parts.append(f"Preview=[{content_preview}]")
        else:
            error_parts.append("Response=(empty)")
        
        # Log everything on one line
        logger.error(" | ".join(error_parts))
    
    def _query_overpass(self, query: str, max_retries: int = 3, latitude: Optional[float] = None, longitude: Optional[float] = None) -> Optional[Dict[str, Any]]:
        """
        Query Overpass API with error handling and retry logic.
        
        Args:
            query: Overpass QL query string
            max_retries: Maximum number of retry attempts
            latitude: Optional latitude coordinate being geocoded (for error logging)
            longitude: Optional longitude coordinate being geocoded (for error logging)
        
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
                        self._log_overpass_failure(response, "Empty Response", "API returned 200 OK but with no content", latitude, longitude)
                        return None
                    
                    # Check if response is HTML/XML instead of JSON
                    content_type = response.headers.get('content-type', '').lower()
                    if 'html' in content_type or 'xml' in content_type:
                        # Server returned an error page instead of JSON
                        self._log_overpass_failure(response, "HTML/XML Error Page", f"Expected JSON but got {content_type}", latitude, longitude)
                        return None
                    
                    try:
                        return response.json()
                    except json.JSONDecodeError as json_err:
                        # Log the response content to help debug
                        self._log_overpass_failure(response, "Invalid JSON", str(json_err), latitude, longitude)
                        return None
                        
                elif response.status_code == 429:  # Rate limited
                    self._log_overpass_failure(response, "Rate Limited", f"Attempt {attempt + 1}/{max_retries}, waiting 60s", latitude, longitude)
                    time.sleep(60)  # Wait 1 minute
                    continue
                elif response.status_code == 504:  # Gateway timeout
                    self._log_overpass_failure(response, "Gateway Timeout", f"Attempt {attempt + 1}/{max_retries}, waiting 5s", latitude, longitude)
                    time.sleep(5)
                    continue
                else:
                    self._log_overpass_failure(response, f"HTTP {response.status_code}", "Unexpected status code", latitude, longitude)
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
    
    def _get_admin_and_protected_areas(self, latitude: float, longitude: float) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]]]:
        """
        Get administrative hierarchy AND protected areas in a single combined query.
        
        This combines two queries that both use is_in() to reduce API calls by 33%.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
        
        Returns:
            Tuple of (admin_dict, protected_areas_list)
            - admin_dict: Dict with 'country', 'state', 'county', 'city' keys
            - protected_areas_list: List of protected area dicts with name and classification info
        """
        # Check cache first for both results
        admin_cache_key = _get_cache_key(latitude, longitude, prefix="geocode:admin")
        protected_cache_key = _get_cache_key(latitude, longitude, prefix="geocode:protected")
        geocoding_cache = _get_geocoding_cache()
        
        cached_admin = geocoding_cache.get(admin_cache_key)
        cached_protected = geocoding_cache.get(protected_cache_key)
        
        # If both are cached, return immediately
        if cached_admin is not None and cached_protected is not None:
            return cached_admin, cached_protected
        
        # Combined query: Get admin boundaries AND protected areas in one API call
        query = f"""
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["admin_level"="2"];
  area.a["admin_level"="4"];
  area.a["admin_level"="6"];
  area.a["admin_level"="8"];
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
  area.a["boundary"="national_park"];
);
out tags;
"""
        
        admin_result = {
            'country': None,
            'state': None,
            'county': None,
            'city': None
        }
        protected_areas = []
        
        response = self._query_overpass(query, latitude=latitude, longitude=longitude)
        if response:
            for element in response.get('elements', []):
                tags = element.get('tags', {})
                name = _get_name_from_tags(tags)
                
                if not name:
                    continue
                
                # Check if this is an admin boundary
                admin_level = tags.get('admin_level')
                boundary = tags.get('boundary', '')
                
                if admin_level and boundary == 'administrative':
                    # Administrative boundary
                    if admin_level == '2':
                        admin_result['country'] = name
                    elif admin_level == '4':
                        admin_result['state'] = name
                    elif admin_level == '6':
                        admin_result['county'] = name
                    elif admin_level == '8':
                        admin_result['city'] = name
                
                # Check if this is a protected area
                if (boundary == 'protected_area' or boundary == 'national_park' or 
                    tags.get('leisure') == 'nature_reserve'):
                    area_info = {
                        'name': name,
                        'protection_title': tags.get('protection_title', ''),
                        'protect_class': tags.get('protect_class', ''),
                        'designation': tags.get('designation', ''),
                        'operator': tags.get('operator', ''),
                        'leisure': tags.get('leisure', ''),
                        'boundary': boundary
                    }
                    protected_areas.append(area_info)
            
            # Cache both results
            geocoding_cache.set(admin_cache_key, admin_result, GEOCODING_CACHE_TTL)
            geocoding_cache.set(protected_cache_key, protected_areas, GEOCODING_CACHE_TTL)
        
        return admin_result, protected_areas
    
    def _get_admin_hierarchy(self, latitude: float, longitude: float) -> Dict[str, Optional[str]]:
        """
        Get administrative hierarchy (country, state, county, city) for a coordinate.
        
        This is a wrapper around _get_admin_and_protected_areas() for backwards compatibility.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
        
        Returns:
            Dict with 'country', 'state', 'county', 'city' keys
        """
        admin_result, _ = self._get_admin_and_protected_areas(latitude, longitude)
        return admin_result
    
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
        response = self._query_overpass(query, latitude=latitude, longitude=longitude)
        if response:
            for element in response.get('elements', []):
                tags = element.get('tags', {})
                name = _get_name_from_tags(tags)
                lat = element.get('lat')
                lon = element.get('lon')
                
                if name and lat is not None and lon is not None:
                    distance = haversine_distance_miles(latitude, longitude, lat, lon)
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
        
        This is a wrapper around _get_admin_and_protected_areas() for backwards compatibility.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
        
        Returns:
            List of protected area dicts with name and classification info
        """
        _, protected_areas = self._get_admin_and_protected_areas(latitude, longitude)
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
        response = self._query_overpass(query, latitude=latitude, longitude=longitude)
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
                        distance = haversine_distance_miles(latitude, longitude, lat, lon)
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
                distance = haversine_distance_miles(latitude, longitude, center_lat, center_lon)
                
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

    def _get_from_cache_or_fetch(
        self, 
        latitude: float, 
        longitude: float
    ) -> Tuple[List[str], List[GeocodingLogMessage]]:
        """
        Internal helper: Check cache first, fetch from API if needed.
        This is the ONLY place that should check/set the top-level tag cache.
        
        Args:
            latitude: Latitude coordinate
            longitude: Longitude coordinate
            
        Returns:
            Tuple of (tags list, log messages list)
        """
        # Check top-level cache for complete tag results
        cache_key = _get_cache_key(latitude, longitude, prefix="geocode:tags")
        geocoding_cache = _get_geocoding_cache()
        cached = geocoding_cache.get(cache_key)
        
        if cached is not None:
            # Return cached tags and empty log messages (already processed)
            return cached, []
        
        # Not in cache - fetch from API via get_location_tags
        tags, log_messages = self.get_location_tags(latitude, longitude)
        
        # Cache the results for 30 days
        geocoding_cache.set(cache_key, tags, GEOCODING_CACHE_TTL)
        
        return tags, log_messages
    
    def batch_geocode_coordinates(
        self,
        coordinates: List[Tuple[float, float]]
    ) -> Dict[Tuple[float, float], Tuple[List[str], List[GeocodingLogMessage]]]:
        """
        THE MAIN ENTRY POINT: Batch geocode multiple coordinates with deduplication.
        
        This is the primary function that should be called from outside for optimal performance.
        
        Features:
        - Deduplicates nearby coordinates (rounded to ~111m precision)
        - Leverages multi-level caching (per-coordinate and top-level tag cache)
        - Minimizes API calls by batching and cache checking
        - Thread-safe coordinate deduplication
        
        Args:
            coordinates: List of (latitude, longitude) tuples
            
        Returns:
            Dict mapping each input coordinate to (tags, log_messages) tuple
            
        Example:
            >>> service = get_reverse_geocoding_service()
            >>> coords = [(40.7128, -74.0060), (40.7128, -74.0061), (34.0522, -118.2437)]
            >>> results = service.batch_geocode_coordinates(coords)
            >>> # coords[0] and coords[1] will be deduplicated (same when rounded)
        """
        if not coordinates:
            return {}
        
        # Step 1: Deduplicate coordinates by rounding to cache precision
        coord_mapping = {}  # Maps rounded coord -> list of original coords
        for lat, lon in coordinates:
            rounded = _round_coordinate(lat, lon)
            if rounded not in coord_mapping:
                coord_mapping[rounded] = []
            coord_mapping[rounded].append((lat, lon))
        
        # Step 2: Fetch results for unique coordinates (cache-aware)
        results = {}
        for rounded_coord in coord_mapping.keys():
            lat, lon = rounded_coord
            tags, log_messages = self._get_from_cache_or_fetch(lat, lon)
            results[rounded_coord] = (tags, log_messages)
        
        # Step 3: Map all original coordinates back to results
        final_results = {}
        for rounded_coord, original_coords in coord_mapping.items():
            result = results[rounded_coord]
            for original_coord in original_coords:
                final_results[original_coord] = result
        
        return final_results
    
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
            # Combined admin+protected query reduces from 3 to 2 API calls (33% reduction)
            with ThreadPoolExecutor(max_workers=2) as executor:
                # Submit tasks: 1) Combined admin+protected, 2) Lakes
                combined_future = executor.submit(self._get_admin_and_protected_areas, latitude, longitude)
                lakes_future = executor.submit(self._search_nearby_lakes, latitude, longitude, self.lake_proximity_miles)
                
                # Wait for results
                admin_info, protected_areas = combined_future.result()
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
