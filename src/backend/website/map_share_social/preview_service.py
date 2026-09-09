import traceback

from django.http import HttpResponse
from django.test import RequestFactory

from api.models import CollectionFeatureMembership, CollectionTagRule, FeatureStore
from api.sharing.models import ShareLink
from api.sharing.public_resolver import PublicShareResolver
from geo_lib.logging.console import get_tagged_logger
from geo_lib.sharing.constants import KIND_COLLECTION, KIND_TAG, MAP_KINDS
from geo_lib.sharing.errors import InvalidShareLink, ShareError, ShareForbidden, ShareUnauthorized
from geo_lib.sharing.social_preview import PreviewKey, hash_extent
from geo_lib.tags.tag_set import TagSet
from website.map_share_social.preview_cache import (
    acquire_preview_lock,
    get_cached_png,
    invalidate_token,
    release_preview_lock,
    store_cached_png,
    wait_for_cached_png,
)
from website.map_share_social.preview_image import (
    SOCIAL_PREVIEW_CACHE_SECONDS,
    get_social_preview_source_id,
    normalize_extent,
    render_social_preview_png,
    resolve_social_preview_raster_source,
)

_logger = get_tagged_logger("social_preview")


def png_response(image_bytes: bytes) -> HttpResponse:
    response = HttpResponse(image_bytes, content_type="image/png")
    response["Cache-Control"] = f"public, max-age={SOCIAL_PREVIEW_CACHE_SECONDS}"
    return response


def get_or_render_preview_png(request, share_id: str, *, wait_for_lock: bool) -> HttpResponse:
    try:
        _info, extent = PublicShareResolver.preview_context(share_id)
    except (InvalidShareLink, ShareUnauthorized, ShareForbidden, ShareError):
        return HttpResponse("Invalid share link", status=404)

    normalized = normalize_extent(extent)
    if normalized is None:
        return HttpResponse("Share has no mappable extent", status=404)

    tile_source = resolve_social_preview_raster_source()
    if tile_source is None:
        return HttpResponse("Configured social preview tile source is invalid", status=500)

    preview_key = PreviewKey(share_id, get_social_preview_source_id(), hash_extent(normalized))
    cache_key = preview_key.cache_key()
    cached_image = get_cached_png(cache_key)
    if cached_image:
        return png_response(cached_image)

    acquired = acquire_preview_lock(share_id)
    if not acquired:
        if not wait_for_lock:
            cached_image = get_cached_png(cache_key)
            if cached_image:
                return png_response(cached_image)
            return HttpResponse(status=202)
        cached_image = wait_for_cached_png(cache_key)
        if cached_image:
            return png_response(cached_image)
        acquired = acquire_preview_lock(share_id)
        if not acquired:
            cached_image = get_cached_png(cache_key)
            if cached_image:
                return png_response(cached_image)
            return HttpResponse("Preview is still generating", status=503)

    try:
        cached_image = get_cached_png(cache_key)
        if cached_image:
            return png_response(cached_image)
        image_bytes = render_social_preview_png(request, normalized, tile_source)
        store_cached_png(share_id, cache_key, image_bytes)
        return png_response(image_bytes)
    except Exception:
        _logger.error(
            "Failed to render social preview image for share_id=%s source=%s:\n%s",
            share_id,
            get_social_preview_source_id(),
            traceback.format_exc(),
        )
        return HttpResponse("Failed to render preview image", status=500)
    finally:
        release_preview_lock(share_id)


def warmup_preview_png(share_id: str) -> None:
    request = RequestFactory().get(f"/share/map/{share_id}/preview.png")
    get_or_render_preview_png(request, share_id, wait_for_lock=False)


def invalidate_previews_for_tokens(tokens) -> None:
    for token in set(tokens):
        if token:
            invalidate_token(token)


def invalidate_previews_for_feature(feature: FeatureStore) -> None:
    if feature is None or feature.scope:
        return
    tokens = list(
        ShareLink.objects.active().map_domain().for_feature(feature.id).values_list("token", flat=True)
    )
    properties = (feature.geojson or {}).get("properties") or {}
    tags = TagSet.from_properties(properties).user
    if tags:
        tokens.extend(
            ShareLink.objects.active().owned_by(feature.user).for_kind(KIND_TAG).filter(
                subject_ref__in=list(tags)
            ).values_list("token", flat=True)
        )
        tokens.extend(
            ShareLink.objects.active().owned_by(feature.user).for_kind(KIND_COLLECTION).filter(
                subject_ref__in=[
                    str(collection_id)
                    for collection_id in CollectionTagRule.objects.filter(
                        collection__user=feature.user,
                        tag__in=list(tags),
                    ).values_list("collection_id", flat=True)
                ]
            ).values_list("token", flat=True)
        )
    pin_collection_ids = [
        str(collection_id)
        for collection_id in CollectionFeatureMembership.objects.filter(
            feature=feature
        ).values_list("collection_id", flat=True)
    ]
    if pin_collection_ids:
        tokens.extend(
            ShareLink.objects.active().owned_by(feature.user).for_kind(KIND_COLLECTION).filter(
                subject_ref__in=pin_collection_ids
            ).values_list("token", flat=True)
        )
    invalidate_previews_for_tokens(tokens)


def invalidate_previews_for_share(link: ShareLink) -> None:
    if link.subject_kind in MAP_KINDS:
        invalidate_token(link.token)
