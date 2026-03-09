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


def _page_url_to_repo_api_url(page_url: str) -> str:
    """Convert a Gitea releases page URL to the base repo API URL."""
    parsed = urlparse(page_url)
    path = (parsed.path or "").strip().lstrip("/")
    if path.endswith("/releases"):
        path = path[: -len("/releases")]
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
    """Fetch latest release, assets, and repo description from Gitea for one repo. Used for cache miss."""
    api_url = _page_url_to_api_url(page_url)
    repo_api_url = _page_url_to_repo_api_url(page_url)
    
    display_name = _display_name_from_page_url(page_url)
    description = ""
    
    def make_result(repo_url, download_url=None, tag=None, asset_list=None):
        return {
            "releases_page_url": page_url,  # Original URL from config
            "repo_url": repo_url,
            "download_url": download_url,
            "tag_name": tag,
            "assets": asset_list or [],
            "display_name": display_name,
            "description": description,
        }

    # If a repo URL in the list does not end with /releases just link to the repo
    repo_page_url = page_url
    if page_url.strip().endswith("/releases"):
        repo_page_url = page_url.strip()[:-len("/releases")]

    if not page_url.strip().endswith("/releases"):
        if repo_api_url and is_url_safe_for_fetch(repo_api_url):
            try:
                repo_resp = requests.get(repo_api_url, timeout=RELEASES_REQUEST_TIMEOUT)
                repo_resp.raise_for_status()
                description = repo_resp.json().get("description") or ""
            except Exception:
                pass
        return make_result(page_url)

    if not api_url or not repo_api_url:
        return make_result(page_url)
    
    if not is_url_safe_for_fetch(api_url) or not is_url_safe_for_fetch(repo_api_url):
        _logger.warning("admin_apps: Gitea URL failed SSRF check: %s", page_url)
        return make_result(page_url)

    # Fetch Repo Info for description
    try:
        repo_resp = requests.get(repo_api_url, timeout=RELEASES_REQUEST_TIMEOUT)
        repo_resp.raise_for_status()
        repo_data = repo_resp.json()
        description = repo_data.get("description") or ""
    except Exception as e:
        _logger.warning("admin_apps: failed to fetch repo info %s: %s", repo_api_url, e)

    # Fetch Releases
    try:
        resp = requests.get(api_url, params={"limit": 5}, timeout=RELEASES_REQUEST_TIMEOUT)
        resp.raise_for_status()
        releases = resp.json()
    except Exception as e:
        _logger.warning("admin_apps: failed to fetch releases %s: %s", page_url, e)
        return make_result(page_url)
    
    tag_name = None
    assets = []
    if isinstance(releases, list) and releases:
        release = releases[0]
        tag_name = release.get("tag_name") or release.get("name")
        for a in release.get("assets") or []:
            name = (a.get("name") or "").strip()
            url = (a.get("browser_download_url") or "").strip()
            
            # exclude source code zip and tar.gz
            if name.lower().endswith((".zip", ".tar.gz")):
                continue
                
            if name and url and is_url_safe_for_fetch(url):
                assets.append({"name": name, "url": url})
    
    # Logic for download_url:
    # if a repo has exactly one file in its release then link to the first available file
    download_url = None
    if len(assets) == 1:
        download_url = assets[0]["url"]
    
    return make_result(repo_page_url, download_url, tag_name, assets)


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
