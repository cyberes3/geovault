from api.sharing.grants import ShareGrantService
from api.sharing.models import ShareLink
from api.sharing.public_resolver import PublicShareResolver
from api.sharing.service import ShareService
from geo_lib.sharing.constants import (
    AUDIENCE_AUTHENTICATED,
    INVALID_SHARE_LINK,
    KIND_LIVE_TRACK,
    KIND_LIVE_TRACK_GROUP,
)
from geo_lib.sharing.errors import InvalidShareLink, ShareUnauthorized
from geo_lib.sharing.share_url import ShareUrl

from .helpers import track_to_response, visible_group_track_ids_for_user
from .models import (
    LiveTrack,
    LiveTrackGroupSubscription,
    VISIBILITY_PRIVATE,
    VISIBILITY_PUBLIC,
    VISIBILITY_SHARED,
)

INVALID_INTERNAL_SHARE_RESPONSE = {"error": INVALID_SHARE_LINK, "code": 404}


def build_live_track_internal_share_url(request, share_id: str) -> str:
    return ShareUrl.track_spa(share_id)


def build_live_track_group_internal_share_url(request, share_id: str) -> str:
    return ShareUrl.track_spa(share_id)


def ensure_track_internal_share(track) -> ShareLink | None:
    if getattr(track, "visibility", VISIBILITY_PRIVATE) == VISIBILITY_PRIVATE:
        return None
    return ShareService.ensure_tracker_link(
        track.user, KIND_LIVE_TRACK, track.id, AUDIENCE_AUTHENTICATED
    )


def ensure_group_internal_share(group) -> ShareLink | None:
    if getattr(group, "visibility", VISIBILITY_PRIVATE) == VISIBILITY_PRIVATE:
        return None
    return ShareService.ensure_tracker_link(
        group.user, KIND_LIVE_TRACK_GROUP, group.id, AUDIENCE_AUTHENTICATED
    )


def sync_track_internal_share(track) -> ShareLink | None:
    if getattr(track, "visibility", VISIBILITY_PRIVATE) == VISIBILITY_PRIVATE:
        ShareService.delete_tracker_links(KIND_LIVE_TRACK, track.id, AUDIENCE_AUTHENTICATED)
        return None
    return ensure_track_internal_share(track)


def sync_group_internal_share(group) -> ShareLink | None:
    if getattr(group, "visibility", VISIBILITY_PRIVATE) == VISIBILITY_PRIVATE:
        ShareService.delete_tracker_links(KIND_LIVE_TRACK_GROUP, group.id, AUDIENCE_AUTHENTICATED)
        return None
    return ensure_group_internal_share(group)


def visible_track_internal_share_for_user(track, user) -> ShareLink | None:
    if not can_user_resolve_track_internal_share(user, track):
        return None
    return ensure_track_internal_share(track)


def visible_group_internal_share_for_user(group, user) -> ShareLink | None:
    if not can_user_resolve_group_internal_share(user, group):
        return None
    return ensure_group_internal_share(group)


def resolve_internal_share_info(share_id: str, user) -> dict | None:
    try:
        payload = PublicShareResolver.info(share_id, user)
    except ShareUnauthorized:
        return None
    except InvalidShareLink:
        return None
    if payload.get("share_access") != "internal":
        return None
    return payload


def resolve_internal_share_data(share_id: str, user) -> dict | None:
    try:
        link = PublicShareResolver.get_active_link(share_id)
        if link.audience != AUDIENCE_AUTHENTICATED:
            return None
        return PublicShareResolver.track(share_id, user)
    except ShareUnauthorized:
        return None
    except InvalidShareLink:
        return None


def can_user_resolve_track_internal_share(user, track) -> bool:
    if not getattr(user, "is_authenticated", False):
        return False
    if track.user_id == user.id:
        return True
    if track.visibility == VISIBILITY_PUBLIC:
        return True
    if track.visibility == VISIBILITY_SHARED:
        return ShareGrantService.has_grant(KIND_LIVE_TRACK, track.id, user)
    return False


def can_user_resolve_group_internal_share(user, group) -> bool:
    if not getattr(user, "is_authenticated", False):
        return False
    if group.user_id == user.id:
        return True
    if group.visibility == VISIBILITY_PUBLIC:
        return True
    if group.visibility == VISIBILITY_SHARED:
        return ShareGrantService.has_grant(KIND_LIVE_TRACK_GROUP, group.id, user)
    return False
