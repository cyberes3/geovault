from django.http import JsonResponse, FileResponse, HttpResponseForbidden
from django.conf import settings
from django.contrib.auth.decorators import login_required
from django.views.decorators.http import require_POST
from django.utils import timezone
import requests

from geo_lib.website.auth import api_or_login_required_401
import os
import time
import zipfile
import io
import logging
from pathlib import Path
from .utils import get_assetlinks, get_keystore_info, get_keystore_base64, get_apk_cache_path
from website.config_loader import get_config_loader

logger = logging.getLogger("website.pwa_mint")

def _get_site_domain():
    """Domain for PWA/APK (package name, assetlinks). Always from config, never request host."""
    config = get_config_loader()
    return config.get_str("site.domain", "geovault.example.com")

def asset_links(request):
    """
    Serves the /.well-known/assetlinks.json file for TWA verification.
    Uses the configured site.domain from YAML so the package name matches the built APK.
    Chrome must verify this to hide the address bar (otherwise it falls back to Custom Tabs).
    """
    domain = _get_site_domain()
    package_name = f"com.geovault.webview.{domain.replace('.', '_')}".lower()
    data = get_assetlinks(package_name)
    response = JsonResponse(data, safe=False)
    response["Content-Type"] = "application/json"
    return response

def _perform_pwa_generation(request):
    """
    Internal helper to perform the actual APK generation.
    Returns (JsonResponse, success_bool).
    """
    # PWABuilder configuration
    config = get_config_loader()
    domain = _get_site_domain()
    protocol = "https" if request.is_secure() else "http"
    site_url = f"{protocol}://{domain}"
    # PWABuilder requires HTTPS URLs for manifest and icons
    https_site_url = f"https://{domain}"
    package_id = f"com.geovault.webview.{domain.replace('.', '_')}".lower()
    
    pwa_builder_url = config.get_with_env_override(
        'extensions.pwa_mint.pwa_builder_url', 
        'PWA_BUILDER_URL', 
        'http://pwabuilder:5858'
    )
    
    keystore_info = get_keystore_info()
    if not keystore_info:
        return JsonResponse({"error": "Failed to generate or retrieve keystore info"}, status=500), False

    build_time = timezone.now()
    # versionName: include build time so APK shows when it was built
    app_version = build_time.strftime('%Y%m%d%H%M')
    # versionCode: unique increasing integer (seconds since epoch; fits in Android's int32)
    app_version_code = int(build_time.timestamp())

    # Cache-bust manifest URL so the container never gets a stale manifest (wrong background_color).
    manifest_cache_bust = int(build_time.timestamp() * 1000)
    web_manifest_url = f"{https_site_url}/manifest.webmanifest?t={manifest_cache_bust}"

    payload = {
        "pwaUrl": site_url,
        "name": "GeoVault",
        "packageId": package_id,
        "launcherName": "GeoVault",
        "appVersion": app_version,
        "appVersionCode": app_version_code,
        "backgroundColor": "#163D8A",
        "themeColor": "#163D8A",
        "display": "standalone",
        "enableNotifications": False,
        "startUrl": "/",
        "iconUrl": f"{https_site_url}/maskable-icon-512x512.png",
        "maskableIconUrl": f"{https_site_url}/maskable-icon-512x512.png",
        "webManifestUrl": web_manifest_url,
        "signingMode": "mine",
        "signing": {
            "file": get_keystore_base64(),
            "alias": keystore_info["alias"],
            "storePassword": keystore_info["store_password"],
            "keyPassword": keystore_info["key_password"]
        },
        "fallbackType": "customtabs",
        "host": domain,
        "navigationColor": "#163D8A",
        "navigationColorDark": "#163D8A",
        "navigationDividerColor": "#163D8A",
        "navigationDividerColorDark": "#163D8A",
        "includeSourceCode": False,
        "splashScreenFadeOutDuration": 300
    }
    
    try:
        start_time = time.time()
        # Pre-call the manifest URL so caches are primed with correct content before the container fetches it.
        try:
            requests.get(web_manifest_url, timeout=10)
        except Exception as e:
            logger.warning("Pre-call of manifest URL failed (container may still succeed): %s", e)

        # Cache-bust URL so first request after Django restart never gets a stale cached zip.
        url = f"{pwa_builder_url}/generateAppPackage?t={int(start_time * 1000)}"
        logger.info(f"Triggering APK generation for {site_url} at {pwa_builder_url}")
        res = requests.post(url, json=payload, timeout=300)
        res.raise_for_status()

        content_type = res.headers.get("Content-Type", "")
        if "zip" not in content_type and "octet-stream" not in content_type:
            logger.warning(f"Unexpected response Content-Type from PWABuilder: {content_type}, length={len(res.content)}")

        # The result is a ZIP file containing the APK
        z = zipfile.ZipFile(io.BytesIO(res.content))
        apk_candidates = [f for f in z.namelist() if f.endswith(".apk")]
        if not apk_candidates:
            logger.error(f"ZIP from PWABuilder contains no .apk file; names: {z.namelist()}")
            return JsonResponse({"error": "Invalid package from builder (no APK in zip). Check server logs."}, status=500), False
        apk_filename = apk_candidates[0]
        apk_data = z.read(apk_filename)

        cache_path = get_apk_cache_path(package_id)
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = cache_path.with_suffix(".apk.tmp")

        try:
            with open(temp_path, "wb") as f:
                f.write(apk_data)
            os.replace(temp_path, cache_path)
            elapsed = time.time() - start_time
            logger.info(f"Successfully generated and cached APK at {cache_path} in {elapsed:.1f}s")
            return JsonResponse({"status": "success", "package_id": package_id}), True
        finally:
            if temp_path.exists():
                try:
                    os.remove(temp_path)
                except OSError:
                    pass
        
    except requests.exceptions.ConnectionError:
        error_msg = "Could not connect to PWABuilder service. Ensure the Docker container is running."
        logger.error(error_msg)
        return JsonResponse({"error": error_msg}, status=503), False
    except requests.exceptions.Timeout:
        error_msg = "Timeout connecting to PWABuilder service. APK generation may take several minutes."
        logger.error(error_msg)
        return JsonResponse({"error": error_msg}, status=504), False
    except Exception as e:
        logger.error(f"Failed to generate APK: {e}")
        return JsonResponse({"error": "Failed to generate APK. Check server logs for details."}, status=500), False


@api_or_login_required_401(allow_api_keys=True)
@require_POST
def admin_force_regenerate_pwa_apk(request):
    """
    Admin-only endpoint to force APK regeneration synchronously.
    Blocks until the build completes (typically 1–2 minutes). Staff or superuser only.
    """
    if not request.user.is_staff and not request.user.is_superuser:
        return JsonResponse({"error": "Forbidden. Staff or superuser required."}, status=403)
    logger.info("Admin %s triggered synchronous PWA APK regeneration", request.user.username)
    response, success = _perform_pwa_generation(request)
    return response


@login_required
def download_pwa_apk(request):
    """
    Authenticated endpoint to download the cached APK.
    If the APK doesn't exist, it's likely being generated by the background worker.
    """
    domain = _get_site_domain()
    package_id = f"com.geovault.webview.{domain.replace('.', '_')}".lower()
    cache_path = get_apk_cache_path(package_id)
    
    # Check if APK exists
    if not cache_path.exists():
        logger.info(f"APK not found at {cache_path}, likely being generated by background worker")
        return JsonResponse({
            "error": "APK is currently being generated. Please try again in a few minutes.",
            "status": "generating"
        }, status=202)  # 202 Accepted - request accepted but not yet processed
    
    # Serve the file (no caching so clients always get the latest build)
    response = FileResponse(open(cache_path, 'rb'), content_type='application/vnd.android.package-archive')
    response['Content-Disposition'] = f'attachment; filename="GeoVault Webview {domain}.apk"'
    response['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0'
    response['Pragma'] = 'no-cache'
    return response
