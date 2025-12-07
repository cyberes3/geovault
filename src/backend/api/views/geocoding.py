"""
Geocoding API view for MapTiler geocoding service.
Provides place search functionality with server-side caching.
"""
import hashlib
from urllib.parse import quote

import requests
from django.core.cache import cache
from django.views.decorators.http import require_http_methods

from api.utils.responses import error_response, success_response
from geo_lib.logging.console import get_access_logger
from website.config_loader import get_config_loader

logger = get_access_logger()

# Cache TTL: 7 days in seconds
GEOCODING_CACHE_TTL = 604800


def _get_cache_key(query: str) -> str:
    """
    Generate cache key for geocoding query.

    Uses a hash to ensure cache keys are safe for memcached (no spaces or special chars).

    Args:
        query: Search query

    Returns:
        Cache key string safe for memcached
    """
    normalized = query.strip().lower()
    # Use hash to create a safe cache key (memcached doesn't like spaces/special chars)
    query_hash = hashlib.md5(normalized.encode('utf-8')).hexdigest()
    return f"geocoding:{query_hash}"


def _clean_feature(feature: dict) -> dict:
    """
    Remove unnecessary fields from geocoding feature to reduce payload size.
    Keeps only essential fields needed by the frontend.
    Transforms GeoJSON geometry into a simple coordinates array.

    Args:
        feature: Raw geocoding feature from MapTiler API
        
    Returns:
        Cleaned feature with only necessary fields, coordinates extracted from geometry
    """
    text = feature.get('text')
    if text:
        text = text.strip()
    else:
        text = None
    
    place_name = feature.get('place_name')
    if place_name:
        place_name = place_name.strip()
    else:
        place_name = None

    # Strip 'text' + ', ' from the start of 'place_name' to avoid redundancy
    if text and place_name:
        text_with_comma = text + ', '
        if place_name.startswith(text_with_comma):
            place_name = place_name[len(text_with_comma):]
        elif place_name.startswith(text + ' '):
            place_name = place_name[len(text + ' '):]
        elif place_name == text:
            place_name = None

    # Extract coordinates from geometry
    coordinates = None
    geometry = feature.get('geometry')
    if geometry and isinstance(geometry, dict):
        coordinates = geometry.get('coordinates')

    return {
        'coordinates': coordinates,
        'id': feature.get('id'),
        'text': text,
        'place_name': place_name,
        'bbox': feature.get('bbox')
    }


@require_http_methods(["GET"])
def geocoding_search(request):
    """
    Search for places using MapTiler Geocoding API.
    
    Query parameters:
        q: Search query string (required)
    
    Returns:
        JSON response with geocoding results or error message
        
    Caching:
        Results are cached server-side for 7 days.
        HTTP Cache-Control header is set to 7 days.
    """
    query = request.GET.get('q', '').strip()

    if not query:
        return error_response("Query parameter 'q' is required", code=400)

    config_loader = get_config_loader()
    api_key = config_loader.get_maptiler_api_key()

    if not api_key:
        return error_response(
            "Geocoding service is not available. MapTiler API key is not configured.",
            code=503
        )

    # Check cache first
    cache_key = _get_cache_key(query)
    cached_result = cache.get(cache_key)
    if cached_result is not None:
        result_data = {
            'data': cached_result
        }
        response = success_response(data=result_data)
        response['Cache-Control'] = 'public, max-age=604800'  # 7 days
        return response

    # Make request to MapTiler Geocoding API
    # API endpoint: https://api.maptiler.com/geocoding/{query}.json?key={api_key}
    # By not specifying 'types' parameter, we get ALL feature types:
    # countries, regions, counties, municipalities, places, addresses, POIs,
    # major landforms (mountains, valleys), continental/marine features, etc.
    # Get site domain for Origin header (MapTiler expects just the domain, no protocol)
    site_domain = config_loader.get_str('site.domain')
    headers = {'Origin': site_domain}
    api_url = f"https://api.maptiler.com/geocoding/{quote(query)}.json"

    # Make two requests to ensure we get geographic features (parks, mountains, etc.)
    # Request 1: Geographic features (POIs, major landforms, places) - this gets parks, mountains, etc.
    params_geographic = {
        'key': api_key,
        'limit': 10,
        'autocomplete': 'true',
        'types': 'poi,major_landform,place,region,county,municipality'  # Focus on geographic features
    }

    # Request 2: All types (including addresses) - to get comprehensive results
    params_all = {
        'key': api_key,
        'limit': 10,
        'autocomplete': 'true'
    }

    geographic_features = []
    all_features = []

    # Make geographic features request first
    geo_response = requests.get(api_url, params=params_geographic, headers=headers, timeout=10)
    if geo_response.status_code != 200:
        logger.error(f"Geocoding API error response: status={geo_response.status_code}, body={geo_response.text}")
        if not geographic_features:
            return error_response(
                f"Geocoding API error: {geo_response.status_code}",
                code=400
            )
    geo_data = geo_response.json()
    geographic_features = geo_data.get('features', [])

    # Make all types request
    api_response = requests.get(api_url, params=params_all, headers=headers, timeout=10)
    if api_response.status_code != 200:
        logger.error(f"Geocoding API error response: status={api_response.status_code}, body={api_response.text}")
        if not geographic_features:
            return error_response(
                f"Geocoding API error: {api_response.status_code}",
                code=400
            )
    else:
        api_data = api_response.json()
        all_features = api_data.get('features', [])

    # Combine results: prioritize geographic features, then add others
    # Create a set of IDs from geographic features to avoid duplicates
    geographic_ids = {f.get('id') for f in geographic_features if f.get('id')}

    # Start with geographic features (parks, mountains, etc.)
    features = list(geographic_features)

    # Add other features that aren't already in geographic results
    for feature in all_features:
        if feature.get('id') not in geographic_ids:
            features.append(feature)

    # Sort features to prioritize geographic features (POIs, major landforms, places) over addresses
    # The API already sorts by relevance, but we can further prioritize geographic features
    def get_feature_priority(feature):
        """Return priority score - higher is better. Geographic features get higher priority."""
        place_types = feature.get('place_type', [])
        properties = feature.get('properties', {})
        kind = properties.get('kind', '')
        place_designation = properties.get('place_designation', '')

        # Highest priority: POIs, major landforms, parks
        if 'poi' in place_types or kind == 'major_landform' or 'park' in place_designation.lower():
            return 100
        # High priority: places, regions, counties, municipalities
        elif any(t in place_types for t in ['place', 'region', 'county', 'municipality']):
            return 80
        # Medium priority: addresses
        elif 'address' in place_types:
            return 50
        # Lower priority: postcodes
        elif 'postcode' in place_types:
            return 30
        # Default
        return 0

    # Sort by priority (descending), then by relevance if available
    features.sort(key=lambda f: (
        -get_feature_priority(f),
        -f.get('relevance', 0)
    ))

    # Limit to top results (API already limits to 10, but keep this for safety)
    features = features[:10]

    # Clean features to remove unnecessary fields and reduce payload size
    cleaned_features = [_clean_feature(f) for f in features]

    # Format response data (wrap in 'data' property to match other endpoints)
    result_data = {
        'data': {
            'query': query,
            'features': cleaned_features
        }
    }

    # Cache the result for 7 days (cache the inner data structure)
    # Cache cleaned features to save space
    cache_data = {
        'query': query,
        'features': cleaned_features
    }
    cache.set(cache_key, cache_data, GEOCODING_CACHE_TTL)

    # Return response with cache headers
    response = success_response(data=result_data)
    response['Cache-Control'] = 'public, max-age=604800'  # 7 days
    return response
