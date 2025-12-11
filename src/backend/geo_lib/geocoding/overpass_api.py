"""
Low-level Overpass API client with error handling and retry logic.

This module handles the raw HTTP communication with the Overpass API,
including error logging, retries, and rate limiting.
"""
import json
import time
from typing import Optional, Dict, Any

import requests
from django.conf import settings

from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger(__name__)


def _log_overpass_failure(
    response: requests.Response,
    error_type: str,
    additional_info: str = "",
    latitude: Optional[float] = None,
    longitude: Optional[float] = None
):
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
        f"URL={settings.OVERPASS_API_URL}"
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
    _logger.error(" | ".join(error_parts))


def query_overpass(
    query: str,
    max_retries: int = 3,
    latitude: Optional[float] = None,
    longitude: Optional[float] = None
) -> Optional[Dict[str, Any]]:
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
    api_url = settings.OVERPASS_API_URL
    timeout = settings.OVERPASS_API_TIMEOUT
    
    for attempt in range(max_retries):
        try:
            response = requests.post(
                api_url,
                data=query,
                timeout=timeout,
                headers={'Content-Type': 'text/plain; charset=utf-8'}
            )

            if response.status_code == 200:
                # Check if response has content before trying to parse JSON
                if not response.content or len(response.content) == 0:
                    _log_overpass_failure(
                        response,
                        "Empty Response",
                        "API returned 200 OK but with no content",
                        latitude,
                        longitude
                    )
                    return None

                # Check if response is HTML/XML instead of JSON
                content_type = response.headers.get('content-type', '').lower()
                if 'html' in content_type or 'xml' in content_type:
                    # Server returned an error page instead of JSON
                    _log_overpass_failure(
                        response,
                        "HTML/XML Error Page",
                        f"Expected JSON but got {content_type}",
                        latitude,
                        longitude
                    )
                    return None

                try:
                    return response.json()
                except json.JSONDecodeError as json_err:
                    # Log the response content to help debug
                    _log_overpass_failure(
                        response,
                        "Invalid JSON",
                        str(json_err),
                        latitude,
                        longitude
                    )
                    return None

            elif response.status_code == 429:  # Rate limited
                _log_overpass_failure(
                    response,
                    "Rate Limited",
                    f"Attempt {attempt + 1}/{max_retries}, waiting 60s",
                    latitude,
                    longitude
                )
                time.sleep(60)  # Wait 1 minute
                continue
            elif response.status_code == 504:  # Gateway timeout
                _log_overpass_failure(
                    response,
                    "Gateway Timeout",
                    f"Attempt {attempt + 1}/{max_retries}, waiting 5s",
                    latitude,
                    longitude
                )
                time.sleep(5)
                continue
            else:
                _log_overpass_failure(
                    response,
                    f"HTTP {response.status_code}",
                    "Unexpected status code",
                    latitude,
                    longitude
                )
                return None

        except requests.exceptions.Timeout:
            _logger.warning(f"Overpass request timeout, attempt {attempt + 1}/{max_retries}")
            if attempt < max_retries - 1:
                time.sleep(2 ** attempt)  # Exponential backoff
        except json.JSONDecodeError:
            # Already handled above, but catch it here in case it happens elsewhere
            pass
        except Exception as e:
            _logger.error(f"Overpass query failed: {e}")
            return None

    return None
