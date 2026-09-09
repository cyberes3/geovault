from crawlerdetect import CrawlerDetect
from django.conf import settings
from django.http import HttpResponse, HttpResponsePermanentRedirect
from django.shortcuts import render

from api.sharing.public_resolver import PublicShareResolver
from geo_lib.sharing.constants import MAP_KINDS, TRACKER_KINDS
from geo_lib.sharing.errors import InvalidShareLink, ShareError, ShareForbidden, ShareUnauthorized
from geo_lib.sharing.share_url import ShareUrl
from geo_lib.sharing.social_preview import metadata_from_share_info
from website.map_share_social.preview_service import get_or_render_preview_png
from website.public_url import public_base_url

_crawler_detect = CrawlerDetect()


def _is_crawler_request(request) -> bool:
    user_agent = request.META.get("HTTP_USER_AGENT", "")
    if not user_agent:
        return False
    return bool(_crawler_detect.is_crawler(user_agent))


def _social_page(request, share_id: str, *, domain: str):
    try:
        info, _extent = PublicShareResolver.preview_context(share_id)
    except (InvalidShareLink, ShareUnauthorized, ShareForbidden, ShareError):
        return HttpResponse("Invalid share link", status=404)

    if domain == "map":
        if info.get("share_type") not in MAP_KINDS:
            return HttpResponse("Invalid share link", status=404)
        frontend_url = ShareUrl.map_spa(share_id)
        preview_image_url = f"{public_base_url()}{ShareUrl.map_social(share_id)}preview.png"
        opening_label = "Opening shared map..."
        open_label = "Open shared map"
    else:
        if info.get("share_type") not in TRACKER_KINDS:
            return HttpResponse("Invalid share link", status=404)
        frontend_url = ShareUrl.track_spa(share_id)
        preview_image_url = ""
        opening_label = "Opening shared track..."
        open_label = "Open shared track"

    if not _is_crawler_request(request):
        return HttpResponsePermanentRedirect(frontend_url)

    metadata = metadata_from_share_info(info)
    base = public_base_url()
    context = {
        "page_title": metadata["title"],
        "page_description": metadata["description"],
        "site_name": getattr(settings, "SITE_NAME", "GeoVault"),
        "canonical_url": f"{base}{request.path}",
        "preview_image_url": preview_image_url,
        "app_url": f"{base}{frontend_url}",
        "opening_label": opening_label,
        "open_label": open_label,
    }
    return render(request, "map_share_social.html", context)


def map_share_social_page(request, share_id):
    return _social_page(request, share_id, domain="map")


def track_share_social_page(request, share_id):
    return _social_page(request, share_id, domain="live_track")


def map_share_social_preview_image(request, share_id):
    return get_or_render_preview_png(request, share_id, wait_for_lock=True)
