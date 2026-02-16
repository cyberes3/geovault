"""
Forward geocoding API view with pluggable backends (MapTiler or Google per geocoding_search_mode).
Provides place search functionality with server-side caching.
Address search (/api/geocoding/address-search/) uses Google Geocoding API only.
"""
import hashlib
from urllib.parse import quote, urlencode

import requests
from django.core.cache import cache
from django.views.decorators.http import require_http_methods

from api.utils.responses import error_response, success_response
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from website.config_loader import get_config_loader

_logger = get_tagged_logger()


class GeocodingBackendError(Exception):
    """Raised by search backends when the provider returns an error (e.g. all requests failed)."""
    pass


# Cache TTL: 7 days in seconds
GEOCODING_CACHE_TTL = 604800

# Google Geocoding API base URL
_GOOGLE_GEOCODE_BASE_URL = "https://maps.googleapis.com/maps/api/geocode/json"


@require_http_methods(["GET"])
@api_or_login_required_401()
def geocoding_search(request):
    """
    Search for places using the configured backend (MapTiler or Google per geocoding_search_mode).

    Query parameters:
        q: Search query string (required)

    Returns:
        JSON response with geocoding results or error message

    Caching:
        Results are cached server-side for 7 days. Cache key includes mode so switching provider does not serve stale results.
    """
    query = request.GET.get('q', '').strip()

    if not query:
        return error_response("Query parameter 'q' is required", code=400)

    config_loader = get_config_loader()
    mode = config_loader.get_geocoding_search_mode()
    if mode is None:
        return error_response(
            "Forward geocoding service is not configured. Set geocoding_search_mode to 'maptiler' or 'google' in config.",
            code=503
        )
    if mode == 'maptiler':
        api_key = config_loader.get_maptiler_api_key()
        key_label = "MapTiler API key"
    else:
        api_key = config_loader.get_google_api_key()
        key_label = "Google API key"

    if not api_key:
        return error_response(
            f"Forward geocoding service is not available. {key_label} is not configured.",
            code=503
        )

    cache_key = _get_cache_key(query, mode)
    cached_result = cache.get(cache_key)
    if cached_result is not None:
        response = success_response(data={'data': cached_result})
        response['Cache-Control'] = 'public, max-age=604800'
        return response

    backend = _get_search_backend(mode)
    try:
        result = backend(query, config_loader)
    except requests.exceptions.Timeout:
        return error_response("Forward geocoding API request timed out", code=504)
    except GeocodingBackendError as e:
        return error_response(str(e), code=400)

    cache.set(cache_key, result, GEOCODING_CACHE_TTL)
    response = success_response(data={'data': result})
    response['Cache-Control'] = 'public, max-age=604800'
    return response


@require_http_methods(["GET"])
@api_or_login_required_401()
def geocoding_address_search(request):
    """
    Search for addresses using Google Geocoding API.
    Returns a minimal array of at most 5 results (coordinates + place_name, no full response).

    Query parameters:
        q: Search query string (required)

    Returns:
        JSON response: { "data": [ { "coordinates": [lng, lat], "place_name": "...", "text": "..."? }, ... ] }
        When Google returns INVALID_REQUEST, responds 200 with data=[] and "error_type": "INVALID_REQUEST" so clients get no results while still seeing the underlying status.
    """
    query = request.GET.get('q', '').strip()

    if not query:
        return error_response("Query parameter 'q' is required", code=400)

    config_loader = get_config_loader()
    api_key = config_loader.get_google_api_key()

    if not api_key:
        return error_response(
            "Address search is not available. Google API key is not configured.",
            code=503
        )

    cache_key = _get_address_cache_key(query)
    cached_result = cache.get(cache_key)
    if cached_result is not None:
        response = success_response(data=cached_result)
        response['Cache-Control'] = 'public, max-age=604800'
        return response

    params = {
        'address': query,
        'key': api_key,
        'language': 'en',
    }
    request_url = _GOOGLE_GEOCODE_BASE_URL + "?" + urlencode(params)
    try:
        api_response = requests.get(request_url, timeout=10)
    except requests.exceptions.Timeout:
        return error_response("Geocoding API request timed out", code=504)

    if api_response.status_code != 200:
        _logger.error(
            f"Google Geocoding API error: status={api_response.status_code}, body={api_response.text}"
        )
        return error_response("Geocoding API request failed", code=400)

    api_data = api_response.json()
    status = api_data.get('status')
    if status == 'ZERO_RESULTS':
        minimal_list = []
        cache.set(cache_key, minimal_list, GEOCODING_CACHE_TTL)
        response = success_response(data=minimal_list)
        response['Cache-Control'] = 'public, max-age=604800'
        return response
    if status == 'INVALID_REQUEST':
        _logger.error(f"Google Geocoding API status={status}, body={api_response.text}")
        payload = {"data": [], "error_type": status}
        cache.set(cache_key, payload, GEOCODING_CACHE_TTL)
        response = success_response(data=payload)
        response['Cache-Control'] = 'public, max-age=604800'
        return response
    if status != 'OK':
        _logger.error(f"Google Geocoding API status={status}, body={api_response.text}")
        return error_response(
            api_data.get('error_message', f"Geocoding API error: {status}"),
            code=400 if status in ('INVALID_REQUEST', 'REQUEST_DENIED') else 502
        )

    results = api_data.get('results', [])
    minimal_list = [_clean_google_address_result(r) for r in results[:5]]
    cache.set(cache_key, minimal_list, GEOCODING_CACHE_TTL)

    response = success_response(data=minimal_list)
    response['Cache-Control'] = 'public, max-age=604800'
    return response


def _get_cache_key(query: str, mode: str = 'maptiler') -> str:
    """
    Generate cache key for forward geocoding query.
    Mode-aware so switching provider does not serve stale results.

    Args:
        query: Search query
        mode: Backend mode ('maptiler' or 'google')

    Returns:
        Cache key string safe for memcached
    """
    normalized = query.strip().lower()
    query_hash = hashlib.md5(normalized.encode('utf-8')).hexdigest()
    return f"geocoding:{mode}:{query_hash}"


def _get_address_cache_key(query: str) -> str:
    """Cache key for address-only search (separate from place search)."""
    normalized = query.strip().lower()
    query_hash = hashlib.md5(normalized.encode('utf-8')).hexdigest()
    return f"geocoding_address:{query_hash}"


def _clean_google_address_result(result: dict) -> dict:
    """
    Reduce a Google Geocoding API result to a minimal object: coordinates and place_name.
    geometry.location is lat/lng; we return [lng, lat] for consistency with GeoJSON.
    """
    place_name = (result.get('formatted_address') or '').strip() or None
    coordinates = None
    geometry = result.get('geometry')
    if geometry and isinstance(geometry, dict):
        loc = geometry.get('location')
        if loc and isinstance(loc, dict):
            lat = loc.get('lat')
            lng = loc.get('lng')
            if lat is not None and lng is not None:
                coordinates = [float(lng), float(lat)]

    out = {
        'coordinates': coordinates,
        'place_name': place_name,
    }
    # Optional short label from address_components (e.g. street_number + route)
    text = _google_address_short_label(result.get('address_components') or [])
    if text:
        out['text'] = text
    return out


def _google_address_short_label(address_components: list) -> str | None:
    """Build a short label from Google address_components (e.g. '1600 Amphitheatre Pkwy')."""
    parts = []
    for comp in address_components:
        types = comp.get('types') or []
        if 'street_number' in types:
            parts.append((comp.get('short_name') or comp.get('long_name') or '').strip())
        elif 'route' in types:
            parts.append((comp.get('long_name') or comp.get('short_name') or '').strip())
    if not parts:
        return None
    return ' '.join(p for p in parts if p)


def _google_result_to_place_feature(result: dict, index: int) -> dict:
    """
    Convert a Google Geocoding API result to place-search feature shape (same as MapTiler).
    Includes bbox from viewport (southwest/northeast -> [west, south, east, north]).
    """
    place_name = (result.get('formatted_address') or '').strip() or None
    coordinates = None
    geometry = result.get('geometry')
    if geometry and isinstance(geometry, dict):
        loc = geometry.get('location')
        if loc and isinstance(loc, dict):
            lat = loc.get('lat')
            lng = loc.get('lng')
            if lat is not None and lng is not None:
                coordinates = [float(lng), float(lat)]

    bbox = None
    if geometry and isinstance(geometry, dict):
        viewport = geometry.get('viewport')
        if viewport and isinstance(viewport, dict):
            sw = viewport.get('southwest')
            ne = viewport.get('northeast')
            if sw and ne and isinstance(sw, dict) and isinstance(ne, dict):
                sw_lat = sw.get('lat')
                sw_lng = sw.get('lng')
                ne_lat = ne.get('lat')
                ne_lng = ne.get('lng')
                if all(x is not None for x in (sw_lat, sw_lng, ne_lat, ne_lng)):
                    bbox = [float(sw_lng), float(sw_lat), float(ne_lng), float(ne_lat)]

    text = _google_address_short_label(result.get('address_components') or [])
    if not text and place_name:
        text = place_name.split(',')[0].strip() if place_name else None

    return {
        'coordinates': coordinates,
        'id': result.get('place_id') or f'google-{index}',
        'text': text,
        'place_name': place_name,
        'bbox': bbox,
    }


def _clean_feature(feature: dict) -> dict:
    """
    Remove unnecessary fields from forward geocoding feature to reduce payload size.
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
    # Lower priority: zip codes
    elif 'postcode' in place_types:
        return 30
    # Default
    return 0


def _search_maptiler(query: str, config_loader) -> dict:
    """
    Place search using MapTiler Forward Geocoding API.
    Returns {"query": str, "features": list} with features in place-search shape.
    Raises requests.exceptions.Timeout on timeout, GeocodingBackendError when all requests fail.
    """
    api_key = config_loader.get_maptiler_api_key()
    if not api_key:
        raise GeocodingBackendError("MapTiler API key is not configured")

    site_domain = config_loader.get_str('site.domain')
    headers = {'Origin': site_domain}
    api_url = f"https://api.maptiler.com/geocoding/{quote(query)}.json"

    params_admin = {
        'key': api_key,
        'limit': 10,
        'autocomplete': 'true',
        'language': 'en',
        'types': 'region,subregion,municipality,joint_municipality',
    }
    params_geographic = {
        'key': api_key,
        'limit': 10,
        'autocomplete': 'true',
        'language': 'en',
        'types': 'poi,major_landform,place,region,subregion,county,municipality,joint_municipality,joint_submunicipality,municipal_district,locality,neighbourhood',
    }
    params_all = {
        'key': api_key,
        'limit': 10,
        'autocomplete': 'true',
        'language': 'en',
    }

    admin_features = []
    geographic_features = []
    all_features = []

    admin_response = requests.get(api_url, params=params_admin, headers=headers, timeout=10)
    if admin_response.status_code == 200:
        admin_features = admin_response.json().get('features', [])
    else:
        _logger.error(f"Forward geocoding API error response: status={admin_response.status_code}, body={admin_response.text}")

    geo_response = requests.get(api_url, params=params_geographic, headers=headers, timeout=10)
    if geo_response.status_code == 200:
        geographic_features = geo_response.json().get('features', [])
    else:
        _logger.error(f"Forward geocoding API error response: status={geo_response.status_code}, body={geo_response.text}")

    api_response = requests.get(api_url, params=params_all, headers=headers, timeout=10)
    if api_response.status_code == 200:
        all_features = api_response.json().get('features', [])
    else:
        _logger.error(f"Forward geocoding API error response: status={api_response.status_code}, body={api_response.text}")

    if not admin_features and not geographic_features and not all_features:
        raise GeocodingBackendError("Forward geocoding API error: all requests failed")

    admin_ids = {f.get('id') for f in admin_features if f.get('id')}
    geographic_ids = {f.get('id') for f in geographic_features if f.get('id')}
    all_ids = admin_ids | geographic_ids

    features = list(admin_features)
    for feature in geographic_features:
        if feature.get('id') not in admin_ids:
            features.append(feature)
    for feature in all_features:
        if feature.get('id') not in all_ids:
            features.append(feature)

    features.sort(key=lambda f: (
        -_get_feature_priority(f, query),
        -f.get('relevance', 0),
    ))
    features = features[:10]
    cleaned_features = [_clean_feature(f) for f in features]
    return {'query': query, 'features': cleaned_features}


def _search_google(query: str, config_loader) -> dict:
    """
    Place search using Google Geocoding API.
    Returns {"query": str, "features": list} with features in same shape as MapTiler (coordinates, text, place_name, bbox, id).
    Raises requests.exceptions.Timeout on timeout, GeocodingBackendError on API error.
    """
    api_key = config_loader.get_google_api_key()
    if not api_key:
        raise GeocodingBackendError("Google API key is not configured")

    params = {
        'address': query,
        'key': api_key,
        'language': 'en',
    }
    request_url = _GOOGLE_GEOCODE_BASE_URL + "?" + urlencode(params)
    api_response = requests.get(request_url, timeout=10)
    if api_response.status_code != 200:
        _logger.error(
            f"Google Geocoding API error: status={api_response.status_code}, body={api_response.text}"
        )
        raise GeocodingBackendError("Geocoding API request failed")

    api_data = api_response.json()
    status = api_data.get('status')
    if status == 'ZERO_RESULTS':
        return {'query': query, 'features': []}
    if status != 'OK':
        _logger.error(f"Google Geocoding API status={status}, body={api_response.text}")
        raise GeocodingBackendError(
            api_data.get('error_message', f"Geocoding API error: {status}")
        )

    results = api_data.get('results', [])
    features = [_google_result_to_place_feature(r, i) for i, r in enumerate(results[:10])]
    return {'query': query, 'features': features}


_SEARCH_BACKENDS = {
    'maptiler': _search_maptiler,
    'google': _search_google,
}


def _get_search_backend(mode: str):
    """Return the search backend callable for the given mode. Unknown mode falls back to maptiler."""
    return _SEARCH_BACKENDS.get(mode, _search_maptiler)
