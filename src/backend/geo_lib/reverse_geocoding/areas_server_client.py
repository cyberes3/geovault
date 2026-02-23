"""
HTTP client for the areas server (admin boundaries, protected areas, nearby lakes, ocean).
Required when reverse geocoding is enabled.
"""
import json
from typing import Any, Dict, List, Optional, Tuple

import requests

from website.settings_utils import get_setting


def query_areas_server(
    latitude: float,
    longitude: float,
) -> Tuple[
    Optional[Dict[str, Optional[str]]],
    Optional[List[Dict[str, str]]],
    List[Dict[str, Any]],
    List[str],
    Optional[str],
    Optional[str],
]:
    """
    Query the areas server for admin hierarchy, protected areas, nearby lakes, ocean, and ski resort at a point.

    Returns (admin_hierarchy, protected_areas, nearby_lakes, oceans, ski_resort, error). On success error is None.
    oceans is a list of 0–2 names; ski_resort is a string or None. On failure returns (None, None, [], [], None, error_message).
    """
    base_url = (get_setting("AREAS_SERVER_URL") or "").strip()
    if not base_url:
        return (
            None,
            None,
            [],
            [],
            None,
            "AREAS_SERVER_URL is not set; required for reverse geocoding.",
        )
    url = base_url.rstrip("/") + "/query"
    timeout = get_setting("AREAS_SERVER_TIMEOUT", 10)
    verify_ssl = get_setting("AREAS_SERVER_VERIFY_SSL", True)
    city_radius_miles = get_setting("AREAS_SERVER_CITY_RADIUS_MILES", 3.0)
    lake_radius_miles = get_setting("LAKE_PROXIMITY_MILES", 1.0)
    try:
        response = requests.get(
            url,
            params={
                "lat": latitude,
                "lon": longitude,
                "city-radius-miles": city_radius_miles,
                "lake-radius-miles": lake_radius_miles,
            },
            timeout=timeout,
            verify=verify_ssl,
        )
    except requests.exceptions.Timeout as e:
        return (None, None, [], [], None, f"areas server request timed out after {timeout}s: {e}")
    except requests.exceptions.RequestException as e:
        return (None, None, [], [], None, f"areas server request failed: {e}")

    if response.status_code != 200:
        return (
            None,
            None,
            [],
            [],
            None,
            f"areas server returned {response.status_code}: {response.text[:200]!r}",
        )

    try:
        data = response.json()
    except json.JSONDecodeError as e:
        return (None, None, [], [], None, f"areas server returned invalid JSON: {e}")

    admin = data.get("admin_hierarchy")
    protected = data.get("protected_areas")
    if admin is None or protected is None:
        return (
            None,
            None,
            [],
            [],
            None,
            "areas server response missing admin_hierarchy or protected_areas",
        )
    nearby_lakes = data.get("nearby_lakes")
    if not isinstance(nearby_lakes, list):
        nearby_lakes = []
    raw_ocean = data.get("ocean")
    if isinstance(raw_ocean, list):
        oceans = raw_ocean
    elif raw_ocean and isinstance(raw_ocean, str):
        oceans = [raw_ocean]
    else:
        oceans = []
    raw_ski = data.get("ski_resort")
    ski_resort = raw_ski if isinstance(raw_ski, str) and raw_ski.strip() else None
    return (admin, protected, nearby_lakes, oceans, ski_resort, None)
