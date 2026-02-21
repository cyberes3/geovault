"""
Low-level Overpass API client with error handling and retry logic.

This module handles the raw HTTP communication with the Overpass API,
including error logging, retries, and rate limiting.
"""
import hashlib
import json
import re
import time
from typing import Optional, Dict, Any, Tuple

import requests
import urllib3
from django.conf import settings

from geo_lib.reverse_geocoding.cache import _REVERSE_GEOCODING_CACHE
from geo_lib.reverse_geocoding.constants import REVERSE_GEOCODING_CACHE_TTL
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
) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
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
        Tuple of (JSON response dict or None, Error message string or None)
        - If successful: (response_dict, None)
        - If failed: (None, error_message)
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
        return cached_response, None

    retry_wait_time = 0
    last_error = "Unknown error"

    for attempt in range(max_retries):
        if attempt > 0:
            time.sleep(retry_wait_time)

        try:
            verify_ssl = settings.OVERPASS_API_VERIFY_SSL
            if not verify_ssl:
                urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
            response = requests.post(
                settings.OVERPASS_API_URL,
                data=query,
                timeout=settings.OVERPASS_API_TIMEOUT,
                headers={'Content-Type': 'text/plain; charset=utf-8'},
                verify=verify_ssl
            )

            if response.status_code == 200:
                # Check if response has content before trying to parse JSON
                content_type = response.headers.get('content-type', '').lower()
                if not response.content or len(response.content) == 0:
                    error_type = "Empty Response"
                    retry_wait_time = 10
                    last_error = f"Overpass API returned empty response (Status 200)"
                # Check if response is HTML/XML instead of JSON
                elif 'html' in content_type or 'xml' in content_type:
                    error_type = "HTML/XML Error Page"
                    retry_wait_time = 10
                    last_error = f"Overpass API returned HTML/XML instead of JSON (Status 200)"
                else:
                    try:
                        json_response = response.json()

                        # Only cache if response contains data (has elements)
                        # Don't cache empty responses from API failures
                        elements = json_response.get('elements', [])
                        if elements:
                            _REVERSE_GEOCODING_CACHE.set(cache_key, json_response, REVERSE_GEOCODING_CACHE_TTL)

                        return json_response, None
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
                        last_error = f"Overpass API returned invalid JSON: {json_err}"
                        return None, last_error

            elif response.status_code == 429:  # Rate limited
                error_type = "Rate Limited"
                retry_wait_time = 60
                last_error = "Overpass API rate limited (429)"
            elif response.status_code == 504:  # Gateway timeout
                error_type = "Gateway Timeout"
                retry_wait_time = 10
                last_error = "Overpass API gateway timeout (504)"
            else:
                # Treat all other non-200 status codes as retryable
                error_type = f"HTTP {response.status_code}"
                retry_wait_time = 10 * (2 ** attempt)
                last_error = f"Overpass API HTTP {response.status_code}"

            retries_left = max_retries - (attempt + 1)
            wait_msg = f"waiting {retry_wait_time}s, " if retries_left > 0 else ""
            retry_msg = f"{retries_left} retries left" if retries_left > 0 else "no retries left"

            _log_overpass_failure(
                response,
                error_type,
                query,
                f"Attempt {attempt + 1}/{max_retries}, {wait_msg}{retry_msg}",
                latitude,
                longitude
            )

            continue

        except requests.exceptions.Timeout:
            # Log query for timeout errors
            query_preview = query[:500].replace('\n', '\\n').replace('\r', '\\r')
            if len(query) > 500:
                query_preview += "... (truncated)"
            coord_info = ""
            if latitude is not None and longitude is not None:
                coord_info = f" | Coordinates=({latitude},{longitude})"

            retry_wait_time = max(10, 2 ** attempt)
            retries_left = max_retries - (attempt + 1)
            wait_msg = f"waiting {retry_wait_time}s, " if retries_left > 0 else ""
            retry_msg = f"{retries_left} retries left" if retries_left > 0 else "no retries left"

            _logger.warning(
                f"Overpass request timeout, attempt {attempt + 1}/{max_retries}, {wait_msg}{retry_msg} | "
                f"Query=[{query_preview}]{coord_info}"
            )
            last_error = f"Overpass API request timeout (Attempt {attempt + 1})"
            continue

        except Exception as e:
            # Log query for general exceptions
            query_preview = query[:500].replace('\n', '\\n').replace('\r', '\\r')
            if len(query) > 500:
                query_preview += "... (truncated)"
            coord_info = ""
            if latitude is not None and longitude is not None:
                coord_info = f" | Coordinates=({latitude},{longitude})"
            _logger.error(f"Overpass query failed: {e} | Query=[{query_preview}]{coord_info}")
            last_error = f"Overpass API query failed: {str(e)}"
            return None, last_error

    return None, last_error
