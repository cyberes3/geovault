"""
API view to expose latest Android app release download URLs from Gitea.
Fetches the latest release and matches Uploader / Places APK assets by name.
"""

import requests
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel, Field

from geo_lib.logging.console import get_tagged_logger
from geo_lib.security.ssrf import is_url_safe_for_fetch
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()

# Hardcoded: always git.evulid.cc (not configurable)
RELEASES_API_URL = "https://git.evulid.cc/api/v1/repos/cyberes/geovault-app-release/releases"
RELEASES_PAGE_URL = "https://git.evulid.cc/cyberes/geovault-app-release/releases"
RELEASES_REQUEST_TIMEOUT = 10
# 1 hour cache for server and browser
CACHE_MAX_AGE_SECONDS = 3600


class AppReleasesResponse(BaseModel):
    """Response shape for the app releases endpoint."""

    uploader_url: str | None = Field(default=None, description="Direct download URL for latest Uploader APK")
    places_url: str | None = Field(default=None, description="Direct download URL for latest Places APK")
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


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_app_releases(request):
    """
    Return the latest Uploader and Places APK download URLs from the Gitea releases API (git.evulid.cc).
    Requires authentication (session or API key). If the API is unreachable or returns no matching
    assets, URLs are null and clients should use releases_page_url.
    """
    if not is_url_safe_for_fetch(RELEASES_API_URL):
        _logger.warning("app_releases: releases API URL failed SSRF check, returning fallback only")
        body = AppReleasesResponse(uploader_url=None, places_url=None, releases_page_url=RELEASES_PAGE_URL)
        response = JsonResponse(body.model_dump())
        response["Cache-Control"] = f"public, max-age={CACHE_MAX_AGE_SECONDS}"
        return response

    uploader_url = None
    places_url = None

    try:
        resp = requests.get(RELEASES_API_URL, params={"limit": 1}, timeout=RELEASES_REQUEST_TIMEOUT)
        resp.raise_for_status()
        releases = resp.json()
    except requests.RequestException as e:
        _logger.warning("app_releases: failed to fetch releases: %s", e)
        body = AppReleasesResponse(
            uploader_url=None, places_url=None, releases_page_url=RELEASES_PAGE_URL
        )
        response = JsonResponse(body.model_dump())
        response["Cache-Control"] = f"public, max-age={CACHE_MAX_AGE_SECONDS}"
        return response
    except (ValueError, TypeError) as e:
        _logger.warning("app_releases: invalid JSON from releases API: %s", e)
        body = AppReleasesResponse(
            uploader_url=None, places_url=None, releases_page_url=RELEASES_PAGE_URL
        )
        response = JsonResponse(body.model_dump())
        response["Cache-Control"] = f"public, max-age={CACHE_MAX_AGE_SECONDS}"
        return response

    if isinstance(releases, list) and len(releases) > 0:
        release = releases[0]
        assets = release.get("assets") or []
        uploader_url = _find_uploader_asset(assets)
        places_url = _find_places_asset(assets)

    body = AppReleasesResponse(
        uploader_url=uploader_url,
        places_url=places_url,
        releases_page_url=RELEASES_PAGE_URL,
    )
    response = JsonResponse(body.model_dump())
    response["Cache-Control"] = f"public, max-age={CACHE_MAX_AGE_SECONDS}"
    return response
