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
from geo_lib.website.auth import api_or_login_required_401
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
    
    For non-English place names, uses matching_text and matching_place_name
    when available to provide English-friendly display names.

    Args:
        feature: Raw geocoding feature from MapTiler API
        
    Returns:
        Cleaned feature with only necessary fields, coordinates extracted from geometry
    """
    # Prefer matching_text over text for non-English names (e.g., Shanghai has text="上海市" but matching_text="Shanghai")
    matching_text = feature.get('matching_text')
    text = feature.get('text')
    
    # Use matching_text if available, otherwise use text
    if matching_text:
        text = matching_text.strip()
    elif text:
        text = text.strip()
    else:
        text = None

    # Prefer matching_place_name over place_name for non-English names
    matching_place_name = feature.get('matching_place_name')
    place_name = feature.get('place_name')
    
    # Use matching_place_name if available, otherwise use place_name
    if matching_place_name:
        place_name = matching_place_name.strip()
    elif place_name:
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


def _get_feature_priority(feature, query):
    """Return priority score - higher is better. Geographic features get higher priority."""
    place_types = feature.get('place_type', [])
    properties = feature.get('properties', {})
    kind = properties.get('kind', '')
    place_designation = properties.get('place_designation', '')
    text = feature.get('text', '').lower()
    matching_text = feature.get('matching_text', '').lower()
    matching_place_name = feature.get('matching_place_name', '').lower()
    query_lower = query.lower()

    # Check if the feature text matches the query exactly (for city-level places)
    # Also check matching_text and matching_place_name for non-English names
    # (e.g., Shanghai has text="上海市" but matching_text="Shanghai")
    is_exact_match = (
        text == query_lower or
        matching_text == query_lower or
        (matching_place_name and matching_place_name.startswith(query_lower + ',')) or
        (matching_place_name and matching_place_name.startswith(query_lower + ' '))
    )

    # Administrative/geographic place types that should be prioritized (all status: true per MapTiler docs)
    admin_place_types = [
        'place', 'region', 'subregion', 'county', 'municipality',
        'joint_municipality', 'joint_submunicipality', 'municipal_district',
        'locality', 'neighbourhood', 'country'
    ]

    # Highest priority: Major administrative divisions (cities, states, provinces) that match query exactly
    # This ensures major cities like "London, UK" and "Shanghai, China" appear above smaller towns
    if is_exact_match and any(t in place_types for t in admin_place_types):
        if place_designation == 'city':
            return 120  # Cities get highest priority
        elif place_designation in ('state', 'province', 'region'):
            return 115  # States/provinces get very high priority (e.g., Shanghai is a direct-administered municipality)
        return 110  # Other municipalities/towns get high priority
    # High priority: POIs, major landforms, parks
    elif 'poi' in place_types or kind == 'major_landform' or 'park' in place_designation.lower():
        return 100
    # High priority: administrative places (not exact match)
    elif any(t in place_types for t in admin_place_types):
        return 80
    # Medium priority: addresses
    elif 'address' in place_types:
        return 50
    # Lower priority: postcodes
    elif 'postcode' in place_types:
        return 30
    # Default
    return 0


@require_http_methods(["GET"])
@api_or_login_required_401()
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

    # Make three requests to ensure we get comprehensive results including major cities
    # Request 1: Major administrative divisions only (regions, subregions, municipalities)
    # This ensures major cities like Tokyo, Japan are captured even if they're not in the top 10 of other requests
    params_admin = {
        'key': api_key,
        'limit': 10,  # MapTiler API maximum limit
        'autocomplete': 'true',
        'types': 'region,subregion,municipality,joint_municipality'  # Major administrative divisions
    }

    # Request 2: Geographic features (POIs, major landforms, administrative places) - this gets parks, mountains, etc.
    # Include all administrative place types to ensure cities are captured
    params_geographic = {
        'key': api_key,
        'limit': 10,  # MapTiler API maximum limit
        'autocomplete': 'true',
        'types': 'poi,major_landform,place,region,subregion,county,municipality,joint_municipality,joint_submunicipality,municipal_district,locality,neighbourhood'  # Focus on geographic features
    }

    # Request 3: All types (including addresses) - to get comprehensive results
    params_all = {
        'key': api_key,
        'limit': 10,  # MapTiler API maximum limit
        'autocomplete': 'true'
    }

    admin_features = []
    geographic_features = []
    all_features = []

    try:
        # Make administrative divisions request first (highest priority for major cities)
        admin_response = requests.get(api_url, params=params_admin, headers=headers, timeout=10)
        if admin_response.status_code == 200:
            admin_data = admin_response.json()
            admin_features = admin_data.get('features', [])
        else:
            logger.error(f"Geocoding API error response: status={admin_response.status_code}, body={admin_response.text}")

        # Make geographic features request
        geo_response = requests.get(api_url, params=params_geographic, headers=headers, timeout=10)
        if geo_response.status_code == 200:
            geo_data = geo_response.json()
            geographic_features = geo_data.get('features', [])
        else:
            logger.error(f"Geocoding API error response: status={geo_response.status_code}, body={geo_response.text}")

        # Make all types request
        api_response = requests.get(api_url, params=params_all, headers=headers, timeout=10)
        if api_response.status_code == 200:
            api_data = api_response.json()
            all_features = api_data.get('features', [])
        else:
            logger.error(f"Geocoding API error response: status={api_response.status_code}, body={api_response.text}")
    except requests.exceptions.Timeout:
        return error_response("Geocoding API request timed out", code=504)
    except requests.exceptions.RequestException as e:
        logger.error(f"Geocoding API request error: {e}")
        return error_response("Geocoding API request failed", code=500)

    # If all requests failed, return error
    if not admin_features and not geographic_features and not all_features:
        return error_response(
            "Geocoding API error: All requests failed",
            code=400
        )

    # Combine results: prioritize administrative divisions, then geographic features, then others
    # Create sets of IDs to avoid duplicates
    admin_ids = {f.get('id') for f in admin_features if f.get('id')}
    geographic_ids = {f.get('id') for f in geographic_features if f.get('id')}
    all_ids = admin_ids | geographic_ids

    # Start with administrative divisions (major cities/regions)
    features = list(admin_features)

    # Add geographic features that aren't already in admin results
    for feature in geographic_features:
        if feature.get('id') not in admin_ids:
            features.append(feature)

    # Add other features that aren't already in results
    for feature in all_features:
        if feature.get('id') not in all_ids:
            features.append(feature)

    # Sort features to prioritize geographic features (POIs, major landforms, places) over addresses
    # The API already sorts by relevance, but we can further prioritize geographic features

    # Sort by priority (descending), then by relevance if available
    features.sort(key=lambda f: (
        -_get_feature_priority(f, query),
        -f.get('relevance', 0)
    ))

    # Limit to top results (prioritized by city-level exact matches, then POIs, then others)
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
