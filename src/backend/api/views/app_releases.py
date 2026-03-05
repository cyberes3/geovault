"""
API view to expose latest Android app release download URLs from Gitea.
Fetches the latest release and matches Uploader, Places, and Tracker APK assets by name.
Response is cached server-side for 30 minutes so the Gitea API is not hit on every request.
"""

import os

import requests
from django.core.cache import cache
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel, Field

from geo_lib.logging.console import get_tagged_logger
from geo_lib.security.ssrf import is_url_safe_for_fetch
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()

# Default: git.evulid.cc. Overridable via APP_RELEASES_API_URL (undocumented).
DEFAULT_RELEASES_API_URL = "https://git.evulid.cc/api/v1/repos/cyberes/geovault-app-release/releases"
RELEASES_REQUEST_TIMEOUT = 10
# 30 minutes server and browser cache
CACHE_MAX_AGE_SECONDS = 30 * 60
APP_RELEASES_CACHE_KEY_PREFIX = "api:app_releases"


def _get_releases_api_url() -> str:
    return (os.environ.get("APP_RELEASES_API_URL") or "").strip() or DEFAULT_RELEASES_API_URL


def _get_releases_page_url(api_url: str) -> str:
    if "/api/v1/repos/" in api_url:
        return api_url.replace("/api/v1/repos/", "/", 1)
    return "https://git.evulid.cc/cyberes/geovault-app-release/releases"


class AppReleasesResponse(BaseModel):
    """Response shape for the app releases endpoint."""

    uploader_url: str | None = Field(default=None, description="Direct download URL for latest Uploader APK")
    places_url: str | None = Field(default=None, description="Direct download URL for latest Places APK")
    tracker_url: str | None = Field(default=None, description="Direct download URL for latest Tracker APK")
    releases_page_url: str = Field(description="URL to the releases page (fallback)")


def _find_uploader_asset(assets: list) -> str | None:
    """Return browser_download_url for the first asset whose name suggests Uploader APK."""
    for a in assets:
        name = (a.get("name") or "").strip()
        if "Uploader" in name and name.lower().endswith(".apk"):
            url = (a.get("browser_download_url") or "").strip()
            if url and is_url_safe_for_fetch(url):
                return url
    return None


def _find_places_asset(assets: list) -> str | None:
    """Return browser_download_url for the first asset whose name matches 'GeoVault Places [release info].apk'."""
    for a in assets:
        name = (a.get("name") or "").strip()
        if name.startswith("GeoVault Places ") and name.lower().endswith(".apk"):
            url = (a.get("browser_download_url") or "").strip()
            if url and is_url_safe_for_fetch(url):
                return url
    return None


def _find_tracker_asset(assets: list) -> str | None:
    """Return browser_download_url for the first asset whose name contains 'GeoVault Live Tracker' and ends with .apk."""
    for a in assets:
        name = (a.get("name") or "").strip()
        if "GeoVault Live Tracker" in name and name.lower().endswith(".apk"):
            url = (a.get("browser_download_url") or "").strip()
            if url and is_url_safe_for_fetch(url):
                return url
    return None


def _fetch_app_releases_data(api_url: str, page_url: str) -> dict:
    """Fetch release URLs from Gitea and return AppReleasesResponse as a dict. Used for cache miss."""
    if not is_url_safe_for_fetch(api_url):
        _logger.warning("app_releases: releases API URL failed SSRF check, returning fallback only")
        return AppReleasesResponse(
            uploader_url=None, places_url=None, tracker_url=None, releases_page_url=page_url
        ).model_dump()

    uploader_url = None
    places_url = None
    tracker_url = None

    try:
        resp = requests.get(
            api_url, params={"limit": 20}, timeout=RELEASES_REQUEST_TIMEOUT
        )
        resp.raise_for_status()
        releases = resp.json()
    except requests.RequestException as e:
        _logger.warning("app_releases: failed to fetch releases: %s", e)
        return AppReleasesResponse(
            uploader_url=None, places_url=None, tracker_url=None, releases_page_url=page_url
        ).model_dump()
    except (ValueError, TypeError) as e:
        _logger.warning("app_releases: invalid JSON from releases API: %s", e)
        return AppReleasesResponse(
            uploader_url=None, places_url=None, tracker_url=None, releases_page_url=page_url
        ).model_dump()

    if isinstance(releases, list):
        for release in releases:
            assets = release.get("assets") or []
            if uploader_url is None:
                uploader_url = _find_uploader_asset(assets)
            if places_url is None:
                places_url = _find_places_asset(assets)
            if tracker_url is None:
                tracker_url = _find_tracker_asset(assets)
            if uploader_url and places_url and tracker_url:
                break

    return AppReleasesResponse(
        uploader_url=uploader_url,
        places_url=places_url,
        tracker_url=tracker_url,
        releases_page_url=page_url,
    ).model_dump()


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_app_releases(request):
    """
    Return the latest Uploader, Places, and Tracker APK download URLs from the Gitea releases API (git.evulid.cc).
    Requires authentication (session or API key). If the API is unreachable or returns no matching
    assets, URLs are null and clients should use releases_page_url.
    Response is cached server-side for 30 minutes.
    """
    api_url = _get_releases_api_url()
    page_url = _get_releases_page_url(api_url)
    cache_key = f"{APP_RELEASES_CACHE_KEY_PREFIX}:{api_url}"

    cached = cache.get(cache_key)
    if cached is not None:
        response = JsonResponse(cached)
        response["Cache-Control"] = "private, no-store"
        return response

    data = _fetch_app_releases_data(api_url, page_url)
    cache.set(cache_key, data, timeout=CACHE_MAX_AGE_SECONDS)
    response = JsonResponse(data)
    response["Cache-Control"] = "private, no-store"
    return response
