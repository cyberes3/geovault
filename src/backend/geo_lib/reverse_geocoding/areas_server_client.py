"""
HTTP client for the areas server (admin boundaries, protected areas, lakes, ocean).
Required when reverse geocoding is enabled.
"""
import json
from typing import List, Optional, Tuple

import requests

from geo_lib.reverse_geocoding.areas_server_models import AreasQueryResponse
from website.settings_utils import get_setting


def _get_areas_server_params():
    """Shared query params for GET and POST."""
    return {
        "city-radius-miles": get_setting("AREAS_SERVER_CITY_RADIUS_MILES", 3.0),
        "lake-radius-miles": get_setting("LAKE_PROXIMITY_MILES", 1.0),
    }


def query_areas_server(latitude: float, longitude: float) -> Tuple[Optional[AreasQueryResponse], Optional[str]]:
    """
    Query the areas server for a point. Returns (response, error).
    On success: response is a typed AreasQueryResponse; error is None.
    On failure: response is None, error is message.
    """
    base_url = (get_setting("AREAS_SERVER_URL") or "").strip()
    if not base_url:
        return None, "AREAS_SERVER_URL is not set; required for reverse geocoding."

    url = base_url.rstrip("/") + "/query"
    timeout = get_setting("AREAS_SERVER_TIMEOUT", 10)
    verify_ssl = get_setting("AREAS_SERVER_VERIFY_SSL", True)
    params = {"lat": latitude, "lon": longitude, **_get_areas_server_params()}
    try:
        resp = requests.get(
            url,
            params=params,
            timeout=timeout,
            verify=verify_ssl,
        )
    except requests.exceptions.Timeout as e:
        return None, f"areas server request timed out after {timeout}s: {e}"
    except requests.exceptions.RequestException as e:
        return None, f"areas server request failed: {e}"

    if resp.status_code != 200:
        return None, f"areas server returned {resp.status_code}: {resp.text[:200]!r}"

    try:
        data = resp.json()
    except json.JSONDecodeError as e:
        return None, f"areas server returned invalid JSON: {e}"

    if data.get("admin_hierarchy") is None or data.get("protected_areas") is None:
        return None, "areas server response missing admin_hierarchy or protected_areas"

    try:
        response = AreasQueryResponse.model_validate(data)
    except Exception as e:
        return None, f"areas server response validation failed: {e}"

    return response, None


def query_areas_server_batch(
    points: List[Tuple[float, float]],
) -> Tuple[Optional[List[AreasQueryResponse]], Optional[str]]:
    """
    Batch query the areas server via POST /query.
    Returns (list of AreasQueryResponse in order, None) or (None, error_message).
    Chunks points by AREAS_SERVER_MAX_BATCH_SIZE.
    """
    if not points:
        return [], None

    base_url = (get_setting("AREAS_SERVER_URL") or "").strip()
    if not base_url:
        return None, "AREAS_SERVER_URL is not set; required for reverse geocoding."

    url = base_url.rstrip("/") + "/query"
    timeout = get_setting("AREAS_SERVER_TIMEOUT", 10)
    verify_ssl = get_setting("AREAS_SERVER_VERIFY_SSL", True)
    max_batch = get_setting("AREAS_SERVER_MAX_BATCH_SIZE", 100)
    params = _get_areas_server_params()

    all_responses: List[AreasQueryResponse] = []
    for start in range(0, len(points), max_batch):
        chunk = points[start : start + max_batch]
        body = {"points": [[lat, lon] for lat, lon in chunk]}
        try:
            resp = requests.post(
                url,
                params=params,
                json=body,
                timeout=timeout,
                verify=verify_ssl,
            )
        except requests.exceptions.Timeout as e:
            return None, f"areas server request timed out after {timeout}s: {e}"
        except requests.exceptions.RequestException as e:
            return None, f"areas server request failed: {e}"

        if resp.status_code != 200:
            return None, f"areas server returned {resp.status_code}: {resp.text[:200]!r}"

        try:
            data = resp.json()
        except json.JSONDecodeError as e:
            return None, f"areas server returned invalid JSON: {e}"

        results = data.get("results")
        if not isinstance(results, list) or len(results) != len(chunk):
            return None, "areas server response missing or invalid 'results' array"

        for i, item in enumerate(results):
            try:
                if item.get("admin_hierarchy") is None or item.get("protected_areas") is None:
                    return None, f"areas server result[{i}] missing admin_hierarchy or protected_areas"
                all_responses.append(AreasQueryResponse.model_validate(item))
            except Exception as e:
                return None, f"areas server result[{i}] validation failed: {e}"

    return all_responses, None
