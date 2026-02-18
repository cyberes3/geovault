"""
MapTiler Forward Geocoding API backend for place search.
"""
from urllib.parse import quote

import requests

from geo_lib.logging.console import get_tagged_logger
from geo_lib.search_geocoding.common import GeocodingBackendError
from website.config_loader import get_config_loader

_logger = get_tagged_logger()


def _clean_feature(feature: dict) -> dict:
    """
    Remove unnecessary fields from forward reverse_geocoding feature to reduce payload size.
    Keeps only essential fields needed by the frontend.
    Transforms GeoJSON geometry into a simple coordinates array.
    
    For non-English place names, uses matching_text and matching_place_name
    when available to provide English-friendly display names.

    Args:
        feature: Raw reverse_geocoding feature from MapTiler API
        
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


def _search_maptiler(query: str) -> dict:
    """
    Place search using MapTiler Forward Geocoding API.
    Returns {"query": str, "features": list} with features in place-search shape.
    Raises requests.exceptions.Timeout on timeout, GeocodingBackendError when all requests fail.
    """
    api_key = get_config_loader().get_maptiler_api_key()
    if not api_key:
        raise GeocodingBackendError("MapTiler API key is not configured")

    site_domain = get_config_loader().get_str('site.domain')
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
        _logger.error(f"Forward reverse_geocoding API error response: status={admin_response.status_code}, body={admin_response.text}")

    geo_response = requests.get(api_url, params=params_geographic, headers=headers, timeout=10)
    if geo_response.status_code == 200:
        geographic_features = geo_response.json().get('features', [])
    else:
        _logger.error(f"Forward reverse_geocoding API error response: status={geo_response.status_code}, body={geo_response.text}")

    api_response = requests.get(api_url, params=params_all, headers=headers, timeout=10)
    if api_response.status_code == 200:
        all_features = api_response.json().get('features', [])
    else:
        _logger.error(f"Forward reverse_geocoding API error response: status={api_response.status_code}, body={api_response.text}")

    if not admin_features and not geographic_features and not all_features:
        raise GeocodingBackendError("Forward reverse_geocoding API error: all requests failed")

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
