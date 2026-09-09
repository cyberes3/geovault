from api.sharing.models import ShareLink
from api.sharing.public_resolver import PublicShareResolver
from api.sharing.service import ShareService
from geo_lib.sharing.constants import AUDIENCE_WORLD, KIND_LIVE_TRACK, KIND_LIVE_TRACK_GROUP
from geo_lib.sharing.errors import InvalidShareLink, ShareUnauthorized
from geo_lib.sharing.share_url import ShareUrl

from .internal_share_links import (
    build_live_track_group_internal_share_url,
    build_live_track_internal_share_url,
    resolve_internal_share_info,
    visible_group_internal_share_for_user,
    visible_track_internal_share_for_user,
)


def build_live_track_share_url(request, share_id: str) -> str:
    return ShareUrl.track_social(share_id)


def build_live_track_group_share_url(request, share_id: str) -> str:
    return ShareUrl.track_social(share_id)


def world_link_for_track(track) -> ShareLink | None:
    return ShareService.tracker_link(KIND_LIVE_TRACK, track.id, AUDIENCE_WORLD)


def world_link_for_group(group) -> ShareLink | None:
    return ShareService.tracker_link(KIND_LIVE_TRACK_GROUP, group.id, AUDIENCE_WORLD)


def attach_track_share_fields(payload, track, user, request, *, include_world: bool) -> None:
    internal_share = visible_track_internal_share_for_user(track, user)
    if internal_share:
        payload["internal_share_id"] = internal_share.token
        if request is not None:
            payload["internal_share_url"] = build_live_track_internal_share_url(request, internal_share.token)
    if include_world:
        world_share = world_link_for_track(track)
        if world_share:
            payload["world_share_id"] = world_share.token
            if request is not None:
                payload["world_share_url"] = build_live_track_share_url(request, world_share.token)


def attach_group_share_fields(payload, group, user, request, *, include_world: bool) -> None:
    internal_share = visible_group_internal_share_for_user(group, user)
    if internal_share:
        payload["internal_share_id"] = internal_share.token
        if request is not None:
            payload["internal_share_url"] = build_live_track_group_internal_share_url(request, internal_share.token)
    if include_world:
        world_share = world_link_for_group(group)
        if world_share:
            payload["world_share_id"] = world_share.token
            if request is not None:
                payload["world_share_url"] = build_live_track_group_share_url(request, world_share.token)


def resolve_live_track_share_info(share_id: str, user) -> dict | None:
    try:
        return PublicShareResolver.info(share_id, user)
    except ShareUnauthorized:
        internal_payload = resolve_internal_share_info(share_id, user)
        return internal_payload
    except InvalidShareLink:
        return None
