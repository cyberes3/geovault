"""
Internal HTML page at /api/pages/apps/ listing links to additional apps (Gitea releases).
Staff-only; parses Gitea release data with 30-minute server cache.
"""
from urllib.parse import urlparse, urlunparse

import requests
from django.core.cache import cache
from django.shortcuts import render

from geo_lib.logging.console import get_tagged_logger
from geo_lib.security.ssrf import is_url_safe_for_fetch

_logger = get_tagged_logger()

# Hardcoded Gitea release page URLs for the "More apps" page. Add more as needed.
ADMIN_APPS_GITEA_RELEASE_URLS = [
    "https://git.evulid.cc/cyberes/survey-data-viewer-android/releases",
]
RELEASES_REQUEST_TIMEOUT = 10
ADMIN_APPS_CACHE_KEY_PREFIX = "website:admin_apps_gitea"
CACHE_MAX_AGE_SECONDS = 30 * 60


def _page_url_to_api_url(page_url: str) -> str:
    """Convert a Gitea releases page URL to the API releases URL."""
    parsed = urlparse(page_url)
    path = (parsed.path or "").strip().lstrip("/")
    if not path.endswith("/releases"):
        return ""
    new_path = "api/v1/repos/" + path
    return urlunparse((parsed.scheme, parsed.netloc, new_path, "", "", ""))


def _display_name_from_page_url(page_url: str) -> str:
    """Derive a display name from the repo path, e.g. survey-data-viewer-android -> Survey Data Viewer Android."""
    parsed = urlparse(page_url)
    path = (parsed.path or "").strip().lstrip("/")
    if path.endswith("/releases"):
        path = path[: -len("/releases")]
    parts = path.split("/")
    repo = parts[-1] if parts else path
    words = repo.replace("-", " ").strip().split() or ["Releases"]
    return " ".join(w.capitalize() for w in words)


def _fetch_gitea_release_data(page_url: str) -> dict:
    """Fetch latest release and assets from Gitea for one repo. Used for cache miss."""
    api_url = _page_url_to_api_url(page_url)
    if not api_url:
        return {"releases_page_url": page_url, "tag_name": None, "assets": [], "display_name": _display_name_from_page_url(page_url)}
    if not is_url_safe_for_fetch(api_url):
        _logger.warning("admin_apps: Gitea URL failed SSRF check: %s", page_url)
        return {"releases_page_url": page_url, "tag_name": None, "assets": [], "display_name": _display_name_from_page_url(page_url)}
    try:
        resp = requests.get(api_url, params={"limit": 5}, timeout=RELEASES_REQUEST_TIMEOUT)
        resp.raise_for_status()
        releases = resp.json()
    except requests.RequestException as e:
        _logger.warning("admin_apps: failed to fetch %s: %s", page_url, e)
        return {"releases_page_url": page_url, "tag_name": None, "assets": [], "display_name": _display_name_from_page_url(page_url)}
    except (ValueError, TypeError) as e:
        _logger.warning("admin_apps: invalid JSON from %s: %s", page_url, e)
        return {"releases_page_url": page_url, "tag_name": None, "assets": [], "display_name": _display_name_from_page_url(page_url)}
    tag_name = None
    assets = []
    if isinstance(releases, list) and releases:
        release = releases[0]
        tag_name = release.get("tag_name") or release.get("name")
        for a in release.get("assets") or []:
            name = (a.get("name") or "").strip()
            url = (a.get("browser_download_url") or "").strip()
            if name and url and is_url_safe_for_fetch(url):
                assets.append({"name": name, "url": url})
    return {
        "releases_page_url": page_url,
        "tag_name": tag_name,
        "assets": assets,
        "display_name": _display_name_from_page_url(page_url),
    }


def internal_apps_page(request):
    """
    Public HTML page listing links to additional apps (Gitea releases).
    Served at /api/pages/apps/. Parses Gitea release data with 30-minute server cache.
    """
    app_list = []
    for page_url in ADMIN_APPS_GITEA_RELEASE_URLS:
        cache_key = f"{ADMIN_APPS_CACHE_KEY_PREFIX}:{page_url}"
        cached = cache.get(cache_key)
        if cached is not None:
            app_list.append(cached)
        else:
            data = _fetch_gitea_release_data(page_url)
            cache.set(cache_key, data, timeout=CACHE_MAX_AGE_SECONDS)
            app_list.append(data)
    return render(request, "internal_apps.html", {"app_list": app_list})
