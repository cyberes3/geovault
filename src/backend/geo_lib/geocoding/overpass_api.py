"""
Low-level Overpass API client with error handling and retry logic.

This module handles the raw HTTP communication with the Overpass API,
including error logging, retries, and rate limiting.
"""
import hashlib
import json
import re
import time
from typing import Optional, Dict, Any

import requests
from django.conf import settings

from geo_lib.geocoding.cache import _REVERSE_GEOCODING_CACHE
from geo_lib.geocoding.constants import REVERSE_GEOCODING_CACHE_TTL
from geo_lib.logging.console import get_tagged_logger
from geo_lib.spatial.coordinates import round_coordinate

_logger = get_tagged_logger()


def _normalize_query_for_cache(query: str, latitude: Optional[float] = None, longitude: Optional[float] = None) -> str:
    """
    Normalize query string for cache key generation by rounding coordinates.
    
    Replaces coordinate values in the query string with their rounded equivalents
    so that queries with slightly different coordinates that round to the same value
    will have the same cache key.
    
    Args:
        query: Original Overpass QL query string
        latitude: Optional latitude coordinate (if provided, used to normalize)
        longitude: Optional longitude coordinate (if provided, used to normalize)
    
    Returns:
        Normalized query string with rounded coordinates
    """
    if latitude is None or longitude is None:
        return query
    
    # Round coordinates to cache precision
    lat_rounded, lon_rounded = round_coordinate(latitude, longitude)
    
    # Replace coordinates in the query string
    # Since queries are constructed with f-strings using the exact coordinates,
    # we can do simple string replacement. We replace both with and without spaces
    # to handle different formatting.
    lat_str = str(latitude)
    lon_str = str(longitude)
    lat_rounded_str = str(lat_rounded)
    lon_rounded_str = str(lon_rounded)
    
    # Replace coordinates (handle both with and without spaces around comma)
    query = query.replace(f'{lat_str},{lon_str}', f'{lat_rounded_str},{lon_rounded_str}')
    query = query.replace(f'{lat_str}, {lon_str}', f'{lat_rounded_str}, {lon_rounded_str}')
    
    return query


def _log_overpass_failure(
        response: requests.Response,
        error_type: str,
        query: str = "",
        additional_info: str = "",
        latitude: Optional[float] = None,
        longitude: Optional[float] = None
):
    """
    Log comprehensive information about an Overpass API failure.
    
    Args:
        response: The requests Response object
        error_type: Type of error (e.g., "Invalid JSON", "Empty Response", "Rate Limited")
        query: The Overpass query string that failed
        additional_info: Additional error information to log
        latitude: Optional latitude coordinate being geocoded
        longitude: Optional longitude coordinate being geocoded
    """
    status_code = response.status_code
    content_type = response.headers.get('content-type', 'unknown')
    content_length = len(response.content) if response.content else 0

    # Get response preview and strip HTML tags
    content_preview = ""
    if response.text:
        content_preview = response.text
        # Strip HTML tags
        content_preview = re.sub(r'<[^>]+>', '', content_preview)
        # Clean up extra whitespace
        content_preview = re.sub(r'\s+', ' ', content_preview).strip()
        # Replace newlines with escaped version for single-line logging
        content_preview = content_preview.replace('\n', '\\n').replace('\r', '\\r')

    # Get query preview (truncated to 500 chars, with newlines escaped)
    query_preview = ""
    if query:
        query_preview = query[:500]
        query_preview = query_preview.replace('\n', '\\n').replace('\r', '\\r')
        if len(query) > 500:
            query_preview += "... (truncated)"

    # Build complete error message on one line
    error_parts = [
        f"Overpass API Failure: {error_type}",
        f"Status={status_code}",
        f"Content-Type={content_type}",
        f"Length={content_length}bytes",
        f"URL={settings.OVERPASS_API_URL}"
    ]

    # Add query if available
    if query_preview:
        error_parts.append(f"Query=[{query_preview}]")

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
    _logger.error(" | ".join(error_parts))


def query_overpass(
        query: str,
        max_retries: int = 3,
        latitude: Optional[float] = None,
        longitude: Optional[float] = None
) -> Optional[Dict[str, Any]]:
    """
    Query Overpass API with error handling and retry logic.
    
    Caches successful API responses to avoid redundant requests.
    Only caches responses that contain data (doesn't cache empty/failed responses).
    
    Args:
        query: Overpass QL query string
        max_retries: Maximum number of retry attempts
        latitude: Optional latitude coordinate being geocoded (for error logging and cache key)
        longitude: Optional longitude coordinate being geocoded (for error logging and cache key)
    
    Returns:
        JSON response dict or None on failure
    """
    # Normalize query string for cache key generation (rounds coordinates in query)
    normalized_query = _normalize_query_for_cache(query, latitude, longitude)
    
    # Generate cache key from normalized query hash and coordinates (if provided)
    query_hash = hashlib.sha256(normalized_query.encode('utf-8')).hexdigest()[:16]
    if latitude is not None and longitude is not None:
        # Use rounded coordinates for cache key to deduplicate nearby queries
        lat_rounded, lon_rounded = round_coordinate(latitude, longitude)
        cache_key = f"overpass:query:{query_hash}:{lat_rounded},{lon_rounded}"
    else:
        # If no coordinates provided, just use query hash
        cache_key = f"overpass:query:{query_hash}"

    # Check cache first
    cached_response = _REVERSE_GEOCODING_CACHE.get(cache_key)
    if cached_response is not None:
        return cached_response

    for attempt in range(max_retries):
        try:
            response = requests.post(
                settings.OVERPASS_API_URL,
                data=query,
                timeout=settings.OVERPASS_API_TIMEOUT,
                headers={'Content-Type': 'text/plain; charset=utf-8'}
            )

            if response.status_code == 200:
                # Check if response has content before trying to parse JSON
                if not response.content or len(response.content) == 0:
                    retries_left = max_retries - (attempt + 1)
                    if attempt < max_retries - 1:
                        _log_overpass_failure(
                            response,
                            "Empty Response",
                            query,
                            f"Attempt {attempt + 1}/{max_retries}, waiting 10s, {retries_left} retries left",
                            latitude,
                            longitude
                        )
                        time.sleep(10)
                        continue
                    else:
                        _log_overpass_failure(
                            response,
                            "Empty Response",
                            query,
                            f"Attempt {attempt + 1}/{max_retries}, no retries left",
                            latitude,
                            longitude
                        )
                        return None

                # Check if response is HTML/XML instead of JSON
                content_type = response.headers.get('content-type', '').lower()
                if 'html' in content_type or 'xml' in content_type:
                    # Server returned an error page instead of JSON
                    retries_left = max_retries - (attempt + 1)
                    if attempt < max_retries - 1:
                        _log_overpass_failure(
                            response,
                            "HTML/XML Error Page",
                            query,
                            f"Attempt {attempt + 1}/{max_retries}, waiting 10s, {retries_left} retries left",
                            latitude,
                            longitude
                        )
                        time.sleep(10)
                        continue
                    else:
                        _log_overpass_failure(
                            response,
                            "HTML/XML Error Page",
                            query,
                            f"Attempt {attempt + 1}/{max_retries}, no retries left",
                            latitude,
                            longitude
                        )
                        return None

                try:
                    json_response = response.json()

                    # Only cache if response contains data (has elements)
                    # Don't cache empty responses from API failures
                    elements = json_response.get('elements', [])
                    if elements:
                        _REVERSE_GEOCODING_CACHE.set(cache_key, json_response, REVERSE_GEOCODING_CACHE_TTL)

                    return json_response
                except json.JSONDecodeError as json_err:
                    # Log the response content to help debug
                    _log_overpass_failure(
                        response,
                        "Invalid JSON",
                        query,
                        str(json_err),
                        latitude,
                        longitude
                    )
                    return None

            elif response.status_code == 429:  # Rate limited
                retries_left = max_retries - (attempt + 1)
                _log_overpass_failure(
                    response,
                    "Rate Limited",
                    query,
                    f"Attempt {attempt + 1}/{max_retries}, waiting 60s, {retries_left} retries left",
                    latitude,
                    longitude
                )
                time.sleep(60)  # Wait 1 minute
                continue
            elif response.status_code == 504:  # Gateway timeout
                retries_left = max_retries - (attempt + 1)
                _log_overpass_failure(
                    response,
                    "Gateway Timeout",
                    query,
                    f"Attempt {attempt + 1}/{max_retries}, waiting 10s, {retries_left} retries left",
                    latitude,
                    longitude
                )
                time.sleep(10)
                continue
            else:
                _log_overpass_failure(
                    response,
                    f"HTTP {response.status_code}",
                    query,
                    "Unexpected status code",
                    latitude,
                    longitude
                )
                return None

        except requests.exceptions.Timeout:
            # Log query for timeout errors
            query_preview = query[:500].replace('\n', '\\n').replace('\r', '\\r')
            if len(query) > 500:
                query_preview += "... (truncated)"
            coord_info = ""
            if latitude is not None and longitude is not None:
                coord_info = f" | Coordinates=({latitude},{longitude})"
            if attempt < max_retries - 1:
                wait_time = max(10, 2 ** attempt)
                retries_left = max_retries - (attempt + 1)
                _logger.warning(
                    f"Overpass request timeout, attempt {attempt + 1}/{max_retries}, waiting {wait_time}s, {retries_left} retries left | "
                    f"Query=[{query_preview}]{coord_info}"
                )
                time.sleep(wait_time)  # Exponential backoff with minimum 10 seconds
            else:
                _logger.warning(
                    f"Overpass request timeout, attempt {attempt + 1}/{max_retries}, no retries left | "
                    f"Query=[{query_preview}]{coord_info}"
                )
        except json.JSONDecodeError:
            # Already handled above, but catch it here in case it happens elsewhere
            pass
        except Exception as e:
            # Log query for general exceptions
            query_preview = query[:500].replace('\n', '\\n').replace('\r', '\\r')
            if len(query) > 500:
                query_preview += "... (truncated)"
            coord_info = ""
            if latitude is not None and longitude is not None:
                coord_info = f" | Coordinates=({latitude},{longitude})"
            _logger.error(f"Overpass query failed: {e} | Query=[{query_preview}]{coord_info}")
            return None

    return None
