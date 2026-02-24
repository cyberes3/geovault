"""
HTTP client for the areas server (admin boundaries, protected areas, nearby lakes, ocean).
Required when reverse geocoding is enabled.
"""
import json
from typing import Any, Dict, Optional, Tuple

import requests

from website.settings_utils import get_setting


def query_areas_server(latitude: float, longitude: float) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    """
    Query the areas server for a point. Returns (response, error).
    On success: response is a dict with admin_hierarchy, protected_areas, nearby_lakes, ocean (list), ski_resort; error is None.
    On failure: response is None, error is message.
    """
    base_url = (get_setting("AREAS_SERVER_URL") or "").strip()
    if not base_url:
        return (None, "AREAS_SERVER_URL is not set; required for reverse geocoding.")

    url = base_url.rstrip("/") + "/query"
    timeout = get_setting("AREAS_SERVER_TIMEOUT", 10)
    verify_ssl = get_setting("AREAS_SERVER_VERIFY_SSL", True)
    city_radius_miles = get_setting("AREAS_SERVER_CITY_RADIUS_MILES", 3.0)
    lake_radius_miles = get_setting("LAKE_PROXIMITY_MILES", 1.0)
    try:
        resp = requests.get(
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
        return (None, f"areas server request timed out after {timeout}s: {e}")
    except requests.exceptions.RequestException as e:
        return (None, f"areas server request failed: {e}")

    if resp.status_code != 200:
        return (None, f"areas server returned {resp.status_code}: {resp.text[:200]!r}")

    try:
        data = resp.json()
    except json.JSONDecodeError as e:
        return (None, f"areas server returned invalid JSON: {e}")

    admin = data.get("admin_hierarchy")
    protected = data.get("protected_areas")
    if admin is None or protected is None:
        return (None, "areas server response missing admin_hierarchy or protected_areas")

    nearby_lakes = data.get("nearby_lakes")
    if not isinstance(nearby_lakes, list):
        nearby_lakes = []
    raw_ocean = data.get("ocean")
    ocean = raw_ocean if isinstance(raw_ocean, list) else ([raw_ocean] if raw_ocean and isinstance(raw_ocean, str) else [])
    raw_ski = data.get("ski_resort")
    ski_resort = raw_ski if isinstance(raw_ski, str) and raw_ski.strip() else None

    return (
        {
            "admin_hierarchy": admin,
            "protected_areas": protected,
            "nearby_lakes": nearby_lakes,
            "ocean": ocean,
            "ski_resort": ski_resort,
        },
        None,
    )
