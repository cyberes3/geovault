# Reverse Geocoding Implementation Guide

## Overview

This document describes how to implement reverse geocoding for GeoVault using the Overpass API. After extensive testing, we found that Overpass API provides the most comprehensive and accurate data for:
- City boundaries and proximity searching
- Protected areas (National Forests, Wilderness Areas, State Parks)
- Administrative hierarchies (country, state, county)

## Why Overpass API?

### Comparison of Geocoding Services

| Feature | MapTiler | Nominatim | Overpass |
|---------|----------|-----------|----------|
| City boundaries | ✅ Accurate | ✅ Accurate | ✅ Accurate |
| Nearby cities (proximity) | ❌ No | ❌ No | ✅ Yes |
| National Forests | ⚠️ Partial | ❌ No | ✅ Yes |
| Wilderness Areas | ❌ No | ❌ No | ✅ Yes |
| State/National Parks | ⚠️ Partial | ⚠️ Partial | ✅ Yes |
| Free/Open Source | ❌ No (paid) | ✅ Yes | ✅ Yes |
| Rate Limits | Moderate | Strict | Moderate |

**Key Finding:** Only Overpass API can find cities/towns that are **near but don't contain** a point.

## Use Cases

### 1. City Tagging with Proximity

**Requirement:** Tag points with `city:Name` if:
- Point is inside city boundaries, OR
- Point is within X miles of city center (even if outside boundaries)

**Example:**
- Point at `39.221644, -105.932657` is 3.72 miles from Fairplay, CO
- Outside Fairplay city limits
- Should still get `city:Fairplay` tag (within 5-mile threshold)

### 2. Protected Area Tagging

**Requirement:** Tag points with protected areas they're in:
- National Forests: `national-forest:Pike National Forest`
- Wilderness Areas: `wilderness:Lost Creek Wilderness`
- National Parks: `national-park:Rocky Mountain`
- State Parks: `state-park:Mueller State Park`
- Research other protected areas to add

**Example:**
- Point at `39.42028, -105.64532` is in:
  - Pike National Forest ✅
  - Lost Creek Wilderness ✅ (but MapTiler misses this!)

## Overpass API Basics

### API Endpoints

**Recommended: Private.coffee (Free, Unlimited)**
```
https://overpass.private.coffee/api/interpreter
```
- Free and unlimited requests
- No rate limits
- Donation-supported non-profit
- hosted by retarded faggots

### Query Format
Overpass uses a custom query language. Queries are sent as POST data.

### Rate Limits
- Private.coffee: Unlimited (free)
- Public instance: ~2 requests/second
- Can self-host for production use

## Implementation Recipes

### Recipe 1: Check if Point is Inside City Boundaries

**Query:**
```overpass
[out:json];
is_in({latitude},{longitude})->.a;
area.a["admin_level"~"^(8)$"];
out tags;
```

**Python Implementation:**
```python
import requests

def get_containing_city(latitude: float, longitude: float) -> Optional[str]:
    """
    Check if a point is inside any city boundary.
    
    Args:
        latitude: Point latitude
        longitude: Point longitude
    
    Returns:
        City name if inside a city, None otherwise
    """
    query = f"""
    [out:json];
    is_in({latitude},{longitude})->.a;
    area.a["admin_level"~"^(8)$"];
    out tags;
    """
    
    response = requests.post(
        'https://overpass.private.coffee/api/interpreter',
        data=query,
        timeout=10
    )
    
    if response.status_code == 200:
        data = response.json()
        for element in data.get('elements', []):
            tags = element.get('tags', {})
            if tags.get('boundary') == 'administrative':
                return tags.get('name')
    
    return None
```

**Example Response:**
```json
{
  "elements": [
    {
      "type": "area",
      "tags": {
        "name": "Aurora",
        "admin_level": "8",
        "boundary": "administrative",
        "place": "city"
      }
    }
  ]
}
```

**Note:** `admin_level=8` is city/town level in the US. Different countries use different levels:
- US: 8 = city/town
- Germany: 8 = municipality
- France: 8 = commune

### Recipe 2: Find Nearby Cities (Proximity Search)

**Use Case:** When point is NOT in any city, find nearest cities within X miles.

**Query:**
```overpass
[out:json];
(
  node["place"~"town|city|village"](around:{radius_meters},{latitude},{longitude});
);
out center;
```

**Python Implementation:**
```python
import math

def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculate distance in miles between two points."""
    R = 3958.8  # Earth radius in miles
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlambda/2)**2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))

def find_nearby_cities(latitude: float, longitude: float, 
                       threshold_miles: float = 5.0) -> List[Dict[str, Any]]:
    """
    Find cities/towns within threshold_miles of a point.
    
    Args:
        latitude: Point latitude
        longitude: Point longitude
        threshold_miles: Search radius in miles
    
    Returns:
        List of dicts with 'name' and 'distance_miles' keys
    """
    # Convert miles to meters (1 mile = 1609.34 meters)
    radius_meters = int(threshold_miles * 1609.34)
    
    query = f"""
    [out:json];
    (
      node["place"~"town|city|village"](around:{radius_meters},{latitude},{longitude});
    );
    out center;
    """
    
    response = requests.post(
        'https://overpass.private.coffee/api/interpreter',
        data=query,
        timeout=10
    )
    
    cities = []
    if response.status_code == 200:
        data = response.json()
        for element in data.get('elements', []):
            tags = element.get('tags', {})
            name = tags.get('name')
            lat = element.get('lat')
            lon = element.get('lon')
            
            if name and lat and lon:
                distance = haversine_distance(latitude, longitude, lat, lon)
                if distance <= threshold_miles:
                    cities.append({
                        'name': name,
                        'distance_miles': distance,
                        'place_type': tags.get('place', '')
                    })
    
    # Sort by distance
    cities.sort(key=lambda x: x['distance_miles'])
    return cities
```

**Example Response:**
```json
{
  "elements": [
    {
      "type": "node",
      "id": 123456,
      "lat": 39.2251889,
      "lon": -106.0019520,
      "tags": {
        "name": "Fairplay",
        "place": "town",
        "population": "724"
      }
    }
  ]
}
```

### Recipe 3: Find Protected Areas (National Forests, Wilderness)

**Query:**
```overpass
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
);
out tags;
```

**Python Implementation:**
```python
def get_protected_areas(latitude: float, longitude: float) -> List[Dict[str, str]]:
    """
    Get all protected areas containing a point.
    
    Returns list of protected areas with their types:
    - National Forests
    - Wilderness Areas
    - National Parks
    - State Parks
    - etc.
    """
    query = f"""
    [out:json];
    is_in({latitude},{longitude})->.a;
    (
      area.a["boundary"="protected_area"];
      area.a["leisure"="nature_reserve"];
    );
    out tags;
    """
    
    response = requests.post(
        'https://overpass.private.coffee/api/interpreter',
        data=query,
        timeout=10
    )
    
    protected_areas = []
    if response.status_code == 200:
        data = response.json()
        for element in data.get('elements', []):
            tags = element.get('tags', {})
            name = tags.get('name')
            
            if name:
                area_info = {
                    'name': name,
                    'protection_title': tags.get('protection_title', ''),
                    'protect_class': tags.get('protect_class', ''),
                    'designation': tags.get('designation', ''),
                    'operator': tags.get('operator', '')
                }
                protected_areas.append(area_info)
    
    return protected_areas
```

**Example Response:**
```json
{
  "elements": [
    {
      "type": "area",
      "tags": {
        "name": "Pike National Forest",
        "boundary": "protected_area",
        "protection_title": "National Forest",
        "protect_class": "6",
        "operator": "United States Forest Service"
      }
    },
    {
      "type": "area",
      "tags": {
        "name": "Lost Creek Wilderness",
        "boundary": "protected_area",
        "leisure": "nature_reserve",
        "protection_title": "Wilderness Area",
        "protect_class": "1b",
        "protected_area": "wilderness_preserve"
      }
    }
  ]
}
```

**Protection Classes (IUCN):**
- `1a` = Strict Nature Reserve
- `1b` = Wilderness Area
- `2` = National Park
- `3` = Natural Monument
- `4` = Habitat/Species Management Area
- `5` = Protected Landscape/Seascape
- `6` = Protected Area with Sustainable Use (National Forests)

### Recipe 4: Get Administrative Hierarchy (Country, State, County)

**Query:**
```overpass
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["admin_level"="2"];  // Country
  area.a["admin_level"="4"];  // State
  area.a["admin_level"="6"];  // County
);
out tags;
```

**Python Implementation:**
```python
def get_admin_hierarchy(latitude: float, longitude: float) -> Dict[str, str]:
    """
    Get country, state, and county for a point.
    
    Returns:
        Dict with 'country', 'state', 'county' keys
    """
    query = f"""
    [out:json];
    is_in({latitude},{longitude})->.a;
    (
      area.a["admin_level"="2"];
      area.a["admin_level"="4"];
      area.a["admin_level"="6"];
    );
    out tags;
    """
    
    response = requests.post(
        'https://overpass.private.coffee/api/interpreter',
        data=query,
        timeout=10
    )
    
    result = {
        'country': None,
        'state': None,
        'county': None
    }
    
    if response.status_code == 200:
        data = response.json()
        for element in data.get('elements', []):
            tags = element.get('tags', {})
            admin_level = tags.get('admin_level')
            name = tags.get('name')
            
            if admin_level == '2':
                result['country'] = name
            elif admin_level == '4':
                result['state'] = name
            elif admin_level == '6':
                result['county'] = name
    
    return result
```

## Complete Implementation Strategy

### Recommended Approach: Hybrid

1. **First: Check if point is inside city boundaries**
   - Use Recipe 1 (`is_in` query with `admin_level=8`)
   - If found, add `city:Name` tag
   - Also get state, county, country from same query

2. **If no city found: Search for nearby cities**
   - Use Recipe 2 (proximity search)
   - Only add city tag if within threshold (e.g., 5 miles)

3. **Get protected areas**
   - Use Recipe 3 (`is_in` query with `boundary=protected_area`)
   - Add tags for all protected areas found:
     - `national-forest:Name`
     - `wilderness:Name`
     - `national-park:Name`

### Sample Combined Function

```python
def get_location_tags(latitude: float, longitude: float, 
                      city_proximity_miles: float = 5.0) -> List[str]:
    """
    Generate comprehensive location tags for a coordinate.
    
    Returns tags in format:
    - city:Name
    - state:Name
    - county:Name
    - country:Name
    - national-forest:Name
    - wilderness:Name
    """
    tags = []
    
    # Step 1: Get administrative hierarchy + check if in city
    query1 = f"""
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
    
    response = requests.post('https://overpass.private.coffee/api/interpreter', 
                            data=query1, timeout=10)
    
    city_found = False
    if response.status_code == 200:
        data = response.json()
        for element in data.get('elements', []):
            tags_dict = element.get('tags', {})
            admin_level = tags_dict.get('admin_level')
            name = tags_dict.get('name')
            
            if admin_level == '2' and name:
                tags.append(f'country:{name}')
            elif admin_level == '4' and name:
                tags.append(f'state:{name}')
            elif admin_level == '6' and name:
                tags.append(f'county:{name}')
            elif admin_level == '8' and name:
                tags.append(f'city:{name}')
                city_found = True
    
    # Step 2: If no city found, search nearby
    if not city_found:
        nearby_cities = find_nearby_cities(latitude, longitude, city_proximity_miles)
        if nearby_cities:
            # Use closest city
            tags.append(f'city:{nearby_cities[0]["name"]}')
    
    # Step 3: Get protected areas
    protected_areas = get_protected_areas(latitude, longitude)
    for area in protected_areas:
        protection_title = area.get('protection_title', '')
        name = area.get('name')
        
        if 'National Forest' in protection_title:
            tags.append(f'national-forest:{name}')
        elif 'Wilderness' in protection_title:
            tags.append(f'wilderness:{name}')
        elif 'National Park' in protection_title:
            tags.append(f'national-park:{name}')
    
    return tags
```

## Performance Considerations

### Caching Strategy

Overpass queries can be slow (500ms - 2s per request). Implement caching:

```python
from functools import lru_cache
from django.core.cache import cache
import hashlib

def get_cache_key(latitude: float, longitude: float) -> str:
    """Generate cache key for coordinate (rounded to ~100m precision)."""
    # Round to 3 decimal places (~111m precision)
    lat_rounded = round(latitude, 3)
    lon_rounded = round(longitude, 3)
    return f"geocode:{lat_rounded},{lon_rounded}"

def get_location_tags_cached(latitude: float, longitude: float) -> List[str]:
    """Get location tags with Redis caching."""
    cache_key = get_cache_key(latitude, longitude)
    
    # Try cache first
    cached = cache.get(cache_key)
    if cached is not None:
        return cached
    
    # Query Overpass
    tags = get_location_tags(latitude, longitude)
    
    # Cache for 30 days
    cache.set(cache_key, tags, timeout=30*24*60*60)
    
    return tags
```

### Rate Limiting

Implement request throttling:

```python
import time
from threading import Lock

class OverpassRateLimiter:
    def __init__(self, requests_per_second: float = 2.0):
        self.min_interval = 1.0 / requests_per_second
        self.last_request_time = 0
        self.lock = Lock()
    
    def wait(self):
        """Wait if necessary to respect rate limit."""
        with self.lock:
            now = time.time()
            time_since_last = now - self.last_request_time
            if time_since_last < self.min_interval:
                time.sleep(self.min_interval - time_since_last)
            self.last_request_time = time.time()

rate_limiter = OverpassRateLimiter(requests_per_second=2.0)

def query_overpass(query: str) -> dict:
    """Query Overpass API with rate limiting."""
    rate_limiter.wait()
    response = requests.post(
        'https://overpass.private.coffee/api/interpreter',
        data=query,
        timeout=10
    )
    return response.json()
```

## Testing

### Test Coordinates

Use these coordinates to test different scenarios:

```python
TEST_CASES = {
    'zurich_center': {
        'lat': 47.3774434,
        'lon': 8.528509,
        'expected': ['city:Zürich', 'state:Zürich', 'country:Switzerland']
    },
    'aurora_denver_border': {
        'lat': 39.7460087121848,
        'lon': -104.8443379809287,
        'expected': ['city:Aurora', 'county:Adams', 'state:Colorado']
    },
    'outside_fairplay': {
        'lat': 39.221644318024545,
        'lon': -105.93265699530785,
        'expected': ['city:Fairplay', 'county:Park', 'state:Colorado'],
        'note': 'Should find Fairplay via proximity (3.72 miles)'
    },
    'pike_national_forest': {
        'lat': 39.04711,
        'lon': -104.97272,
        'expected': ['national-forest:Pike National Forest', 'state:Colorado']
    },
    'lost_creek_wilderness': {
        'lat': 39.42028,
        'lon': -105.64532,
        'expected': [
            'national-forest:Pike National Forest',
            'wilderness:Lost Creek Wilderness',
            'county:Park'
        ]
    },
    'rural_montana': {
        'lat': 47.0527,
        'lon': -109.4396,
        'expected': ['county:Fergus', 'state:Montana'],
        'note': 'No city within threshold'
    }
}
```

## Previous Implementation Notes

### Hybrid Nominatim + Overpass Approach

The previous implementation (removed in commit `fe852f1`) used a hybrid approach:

1. **Nominatim for City Detection**
   - Used Nominatim reverse geocoding API for identifying cities/towns
   - Nominatim is better at handling administrative boundaries
   - Query: `GET /reverse?lat={lat}&lon={lon}&format=json&addressdetails=1`
   - Extracted city from `address.city`, `address.town`, or `address.village`

2. **Overpass for Administrative Hierarchy**
   - Used Overpass `is_in` queries for state, country, and county
   - Query: `is_in({lat},{lon})->.a; relation["admin_level"="2|4|6"](pivot.a);`
   - More reliable for administrative boundaries than Nominatim

3. **Overpass for Protected Areas**
   - Used `is_in` queries with `boundary=protected_area` filter
   - Found National Forests, Wilderness Areas, Parks
   - Query: `is_in({lat},{lon})->.a; relation["boundary"="protected_area"](pivot.a);`

4. **Overpass for Lakes**
   - Used both `is_in` (point inside) and `around` (proximity) queries
   - Searched for `natural=water`, `water=lake`, etc.

**Why It Was Removed:**
- Required self-hosting both Nominatim and Overpass (complex setup)
- Nominatim has strict rate limits (1 request/second)
- Overpass alone can handle all use cases with proper queries

**Key Lessons:**
- City detection can be done with Overpass `admin_level=8` queries
- Proximity search for cities requires Overpass `around` queries (Nominatim can't do this)
- All functionality can be achieved with Overpass alone


### Error Handling

```python
def query_overpass_safe(query: str, max_retries: int = 3) -> Optional[dict]:
    """Query Overpass with retry logic."""
    for attempt in range(max_retries):
        try:
            rate_limiter.wait()
    response = requests.post(
        'https://overpass.private.coffee/api/interpreter',
        data=query,
        timeout=10
    )
            
            if response.status_code == 200:
                return response.json()
            elif response.status_code == 429:  # Rate limited
                time.sleep(60)  # Wait 1 minute
                continue
            elif response.status_code == 504:  # Gateway timeout
                time.sleep(5)
                continue
            else:
                logger.error(f"Overpass error: {response.status_code}")
                return None
                
        except requests.exceptions.Timeout:
            logger.warning(f"Overpass timeout, attempt {attempt + 1}/{max_retries}")
            if attempt < max_retries - 1:
                time.sleep(2 ** attempt)  # Exponential backoff
        except Exception as e:
            logger.error(f"Overpass query failed: {e}")
            return None
    
    return None
```

## References

- [Overpass API Documentation](https://wiki.openstreetmap.org/wiki/Overpass_API)
- [Overpass Turbo (Query Builder)](https://overpass-turbo.eu/)
- [OSM Admin Level Tags](https://wiki.openstreetmap.org/wiki/Tag:boundary%3Dadministrative)
- [IUCN Protected Area Categories](https://www.iucn.org/theme/protected-areas/about/protected-area-categories)

