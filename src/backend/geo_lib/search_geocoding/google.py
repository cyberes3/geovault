"""
Google Geocoding API backend for place search.
"""
from urllib.parse import urlencode

import requests

from geo_lib.logging.console import get_tagged_logger
from geo_lib.search_geocoding.common import GeocodingBackendError
from website.config_loader import get_config_loader

_logger = get_tagged_logger()

# Google Geocoding API base URL
_GOOGLE_GEOCODE_BASE_URL = "https://maps.googleapis.com/maps/api/geocode/json"


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


def _search_google(query: str) -> dict:
    """
    Place search using Google Geocoding API.
    Returns {"query": str, "features": list} with features in same shape as MapTiler (coordinates, text, place_name, bbox, id).
    Raises requests.exceptions.Timeout on timeout, GeocodingBackendError on API error.
    """
    api_key = get_config_loader().get_google_api_key()
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
