"""
HTTP client for the is_in area server (admin boundaries + protected areas).
Required when reverse geocoding is enabled.
"""
import json
from typing import Any, Dict, List, Optional, Tuple

import requests
from django.conf import settings


def query_areas_server(
    latitude: float,
    longitude: float,
) -> Tuple[Optional[Dict[str, Optional[str]]], Optional[List[Dict[str, str]]], Optional[str]]:
    """
    Query the is_in area server for admin hierarchy and protected areas at a point.

    Returns (admin_hierarchy, protected_areas, error). On success error is None.
    On failure returns (None, None, error_message).
    """
    base_url = (getattr(settings, "IS_IN_AREAS_SERVER_URL", None) or "").strip()
    if not base_url:
        return (
            None,
            None,
            "IS_IN_AREAS_SERVER_URL is not set; required for reverse geocoding.",
        )
    url = base_url.rstrip("/") + "/query"
    timeout = getattr(settings, "IS_IN_AREAS_SERVER_TIMEOUT", 10)
    verify_ssl = getattr(settings, "IS_IN_AREAS_SERVER_VERIFY_SSL", True)
    try:
        response = requests.get(
            url,
            params={"lat": latitude, "lon": longitude},
            timeout=timeout,
            verify=verify_ssl,
        )
    except requests.exceptions.Timeout as e:
        return (None, None, f"is_in area server request timed out after {timeout}s: {e}")
    except requests.exceptions.RequestException as e:
        return (None, None, f"is_in area server request failed: {e}")

    if response.status_code != 200:
        return (
            None,
            None,
            f"is_in area server returned {response.status_code}: {response.text[:200]!r}",
        )

    try:
        data = response.json()
    except json.JSONDecodeError as e:
        return (None, None, f"is_in area server returned invalid JSON: {e}")

    admin = data.get("admin_hierarchy")
    protected = data.get("protected_areas")
    if admin is None or protected is None:
        return (
            None,
            None,
            "is_in area server response missing admin_hierarchy or protected_areas",
        )
    return (admin, protected, None)
