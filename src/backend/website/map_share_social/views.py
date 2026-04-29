import traceback

from crawlerdetect import CrawlerDetect
from django.conf import settings
from django.core.cache import cache
from django.http import HttpResponse, HttpResponsePermanentRedirect
from django.shortcuts import render

from api.views.sharing.public_share import resolve_public_share_extent, resolve_public_share_info
from geo_lib.logging.console import get_tagged_logger
from website.map_share_social.preview_image import (
    SOCIAL_PREVIEW_CACHE_SECONDS,
    get_social_preview_source_id,
    normalize_extent,
    render_social_preview_png,
    resolve_social_preview_raster_source,
)
from website.public_url import public_base_url

_crawler_detect = CrawlerDetect()
_logger = get_tagged_logger("social_preview")


def _is_crawler_request(request) -> bool:
    user_agent = request.META.get("HTTP_USER_AGENT", "")
    if not user_agent:
        return False
    return bool(_crawler_detect.is_crawler(user_agent))


def _build_map_share_metadata(share_info: dict):
    share_type = share_info.get("share_type")
    if share_type == "tag":
        subject = share_info.get("tag", "Unknown Tag")
        return {
            "title": f"Shared Map: {subject}",
            "description": f"Shared map for tag {subject} on GeoVault.",
        }
    if share_type == "collection":
        subject = share_info.get("collection_name", "Unknown Collection")
        return {
            "title": f"Shared Map: {subject}",
            "description": f"Shared map for collection {subject} on GeoVault.",
        }
    subject = share_info.get("feature_name", "Unnamed Feature")
    return {
        "title": f"Shared Map: {subject}",
        "description": f"Shared map for feature {subject} on GeoVault.",
    }


def map_share_social_page(request, share_id):
    """
    Render social metadata for map shares and redirect human visitors to the SPA.
    """
    share_info = resolve_public_share_info(share_id)
    if share_info is None:
        return HttpResponse("Invalid share link", status=404)

    frontend_url = f"/#/mapshare?id={share_id}"
    if not _is_crawler_request(request):
        return HttpResponsePermanentRedirect(frontend_url)

    metadata = _build_map_share_metadata(share_info)
    base = public_base_url()
    context = {
        "page_title": metadata["title"],
        "page_description": metadata["description"],
        "site_name": getattr(settings, "SITE_NAME", "GeoVault"),
        "canonical_url": f"{base}{request.path}",
        "preview_image_url": f"{base}/share/map/{share_id}/preview.png",
        "app_url": f"{base}{frontend_url}",
    }
    return render(request, "map_share_social.html", context)


def map_share_social_preview_image(request, share_id):
    """
    Generate a social preview PNG using configured raster tiles over the share extent.
    """
    cache_key = f"social_preview_png:{share_id}:{get_social_preview_source_id()}"
    cached_image = cache.get(cache_key)
    if cached_image:
        response = HttpResponse(cached_image, content_type="image/png")
        response["Cache-Control"] = f"public, max-age={SOCIAL_PREVIEW_CACHE_SECONDS}"
        return response

    share_info = resolve_public_share_info(share_id)
    if share_info is None:
        return HttpResponse("Invalid share link", status=404)

    extent = normalize_extent(resolve_public_share_extent(share_id))
    if extent is None:
        return HttpResponse("Share has no mappable extent", status=404)

    tile_source = resolve_social_preview_raster_source()
    if tile_source is None:
        return HttpResponse("Configured social preview tile source is invalid", status=500)

    try:
        image_bytes = render_social_preview_png(request, extent, tile_source)
    except Exception:
        _logger.error(
            "Failed to render social preview image for share_id=%s source=%s: %s",
            share_id,
            get_social_preview_source_id(),
            traceback.format_exc(),
        )
        return HttpResponse("Failed to render preview image", status=500)

    cache.set(cache_key, image_bytes, timeout=SOCIAL_PREVIEW_CACHE_SECONDS)
    response = HttpResponse(image_bytes, content_type="image/png")
    response["Cache-Control"] = f"public, max-age={SOCIAL_PREVIEW_CACHE_SECONDS}"
    return response
