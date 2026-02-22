"""
HTTP client for the areas server (admin boundaries + protected areas).
Required when reverse geocoding is enabled.
"""
import json
from typing import Any, Dict, List, Optional, Tuple

import requests

from website.settings_utils import get_setting


def query_areas_server(
    latitude: float,
    longitude: float,
) -> Tuple[Optional[Dict[str, Optional[str]]], Optional[List[Dict[str, str]]], Optional[str]]:
    """
    Query the areas server for admin hierarchy and protected areas at a point.

    Returns (admin_hierarchy, protected_areas, error). On success error is None.
    On failure returns (None, None, error_message).
    """
    base_url = (get_setting("AREAS_SERVER_URL") or "").strip()
    if not base_url:
        return (
            None,
            None,
            "AREAS_SERVER_URL is not set; required for reverse geocoding.",
        )
    url = base_url.rstrip("/") + "/query"
    timeout = get_setting("AREAS_SERVER_TIMEOUT", 10)
    verify_ssl = get_setting("AREAS_SERVER_VERIFY_SSL", True)
    try:
        response = requests.get(
            url,
            params={"lat": latitude, "lon": longitude},
            timeout=timeout,
            verify=verify_ssl,
        )
    except requests.exceptions.Timeout as e:
        return (None, None, f"areas server request timed out after {timeout}s: {e}")
    except requests.exceptions.RequestException as e:
        return (None, None, f"areas server request failed: {e}")

    if response.status_code != 200:
        return (
            None,
            None,
            f"areas server returned {response.status_code}: {response.text[:200]!r}",
        )

    try:
        data = response.json()
    except json.JSONDecodeError as e:
        return (None, None, f"areas server returned invalid JSON: {e}")

    admin = data.get("admin_hierarchy")
    protected = data.get("protected_areas")
    if admin is None or protected is None:
        return (
            None,
            None,
            "areas server response missing admin_hierarchy or protected_areas",
        )
    return (admin, protected, None)
