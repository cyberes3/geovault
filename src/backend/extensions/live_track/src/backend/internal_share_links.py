"""Authenticated internal share-link helpers for live trackers and groups."""

import uuid

from api.views.sharing.utils import build_client_share_url, validate_share_id

from .helpers import track_to_response, visible_group_track_ids_for_user
from .models import (
    LiveTrack,
    LiveTrackGroupInternalShare,
    LiveTrackGroupShare,
    LiveTrackGroupSubscription,
    LiveTrackInternalShare,
    LiveTrackShare,
    VISIBILITY_PRIVATE,
    VISIBILITY_PUBLIC,
    VISIBILITY_SHARED,
)

_INTERNAL_SHARE_HASH_PATH = "/#/extensions/live-track/share?id="
INVALID_INTERNAL_SHARE_RESPONSE = {"error": "Invalid share link", "code": 404}


def build_live_track_internal_share_url(request, share_id: str) -> str:
    """Return the relative authenticated tracker share URL for clients to resolve."""
    return build_client_share_url(f"{_INTERNAL_SHARE_HASH_PATH}{share_id}")


def build_live_track_group_internal_share_url(request, share_id: str) -> str:
    """Return the relative authenticated group share URL for clients to resolve."""
    return build_client_share_url(f"{_INTERNAL_SHARE_HASH_PATH}{share_id}")


def ensure_track_internal_share(track) -> LiveTrackInternalShare | None:
    """Create or return the stable internal share for a shared/public track."""
    if getattr(track, "visibility", VISIBILITY_PRIVATE) == VISIBILITY_PRIVATE:
        return None
    share, _ = LiveTrackInternalShare.objects.get_or_create(
        track=track,
        defaults={
            "share_id": _new_internal_share_id(),
            "user": track.user,
        },
    )
    if share.user_id != track.user_id:
        share.user = track.user
        share.save(update_fields=["user"])
    return share


def ensure_group_internal_share(group) -> LiveTrackGroupInternalShare | None:
    """Create or return the stable internal share for a shared/public group."""
    if getattr(group, "visibility", VISIBILITY_PRIVATE) == VISIBILITY_PRIVATE:
        return None
    share, _ = LiveTrackGroupInternalShare.objects.get_or_create(
        group=group,
        defaults={
            "share_id": _new_internal_share_id(),
            "user": group.user,
        },
    )
    if share.user_id != group.user_id:
        share.user = group.user
        share.save(update_fields=["user"])
    return share


def sync_track_internal_share(track) -> LiveTrackInternalShare | None:
    """Ensure shared/public tracks have internal links and private tracks do not."""
    if getattr(track, "visibility", VISIBILITY_PRIVATE) == VISIBILITY_PRIVATE:
        LiveTrackInternalShare.objects.filter(track=track).delete()
        return None
    return ensure_track_internal_share(track)


def sync_group_internal_share(group) -> LiveTrackGroupInternalShare | None:
    """Ensure shared/public groups have internal links and private groups do not."""
    if getattr(group, "visibility", VISIBILITY_PRIVATE) == VISIBILITY_PRIVATE:
        LiveTrackGroupInternalShare.objects.filter(group=group).delete()
        return None
    return ensure_group_internal_share(group)


def visible_track_internal_share_for_user(track, user) -> LiveTrackInternalShare | None:
    """Return the track internal share only when this user can receive/copy it."""
    if not can_user_resolve_track_internal_share(user, track):
        return None
    return ensure_track_internal_share(track)


def visible_group_internal_share_for_user(group, user) -> LiveTrackGroupInternalShare | None:
    """Return the group internal share only when this user can receive/copy it."""
    if not can_user_resolve_group_internal_share(user, group):
        return None
    return ensure_group_internal_share(group)


def resolve_internal_share_info(share_id: str, user) -> dict | None:
    """
    Resolve an authenticated internal share link.

    Track shares take precedence over group shares if a collision somehow exists, matching the
    deterministic lookup contract used by world shares.
    """
    if not validate_share_id(share_id) or not getattr(user, "is_authenticated", False):
        return None

    track_share = (
        LiveTrackInternalShare.objects
        .filter(share_id=share_id)
        .select_related("track", "track__user")
        .first()
    )
    if track_share and can_user_resolve_track_internal_share(user, track_share.track):
        track = track_share.track
        return {
            "share_type": "live_track",
            "track_id": str(track.id),
            "track_name": track.name,
            "created_at": track_share.created_at.isoformat(),
        }

    group_share = (
        LiveTrackGroupInternalShare.objects
        .filter(share_id=share_id)
        .select_related("group", "group__user")
        .first()
    )
    if group_share and can_user_resolve_group_internal_share(user, group_share.group):
        group = group_share.group
        return {
            "share_type": "live_track_group",
            "group_id": str(group.id),
            "group_name": group.name,
            "created_at": group_share.created_at.isoformat(),
        }

    return None


def resolve_internal_share_data(share_id: str, user) -> dict | None:
    """
    Resolve data for the standalone authenticated internal share map.

    The response intentionally matches the world-share data shape, but parameter visibility follows
    recipient/internal-share rules.
    """
    if not validate_share_id(share_id) or not getattr(user, "is_authenticated", False):
        return None

    track_share = (
        LiveTrackInternalShare.objects
        .filter(share_id=share_id)
        .select_related("track", "track__user")
        .first()
    )
    if track_share and can_user_resolve_track_internal_share(user, track_share.track):
        return track_to_response(
            track_share.track,
            include_secret=False,
            is_owner=False,
            all_data=False,
            for_world_share=False,
        )

    group_share = (
        LiveTrackGroupInternalShare.objects
        .filter(share_id=share_id)
        .select_related("group", "group__user")
        .first()
    )
    if group_share and can_user_resolve_group_internal_share(user, group_share.group):
        group = group_share.group
        is_owner = group.user_id == user.id
        is_accepted = is_owner or LiveTrackGroupSubscription.objects.filter(
            user=user, group=group
        ).exists()
        # Internal shares stay governed by the same per-track authorization as the normal
        # authenticated group view: group-level access alone must not expose member tracks
        # the viewer has no individual access to (unlike world shares, which are fully public
        # by the owner's own choice).
        visible_track_ids = set(
            visible_group_track_ids_for_user(group, user, is_owner=is_owner, is_accepted=is_accepted)
        )
        tracks = list(
            LiveTrack.objects.filter(id__in=visible_track_ids).select_related("user").order_by("name")
        )
        track_payloads = [
            track_to_response(
                track,
                include_secret=False,
                is_owner=False,
                all_data=False,
                for_world_share=False,
            )
            for track in tracks
        ]
        return {
            "share_type": "live_track_group",
            "group_name": group.name,
            "tracks": track_payloads,
        }

    return None


def can_user_resolve_track_internal_share(user, track) -> bool:
    """Use tracker sharing rules as the authorization source for internal links."""
    if not getattr(user, "is_authenticated", False):
        return False
    if track.user_id == user.id:
        return True
    if track.visibility == VISIBILITY_PUBLIC:
        return True
    if track.visibility == VISIBILITY_SHARED:
        return LiveTrackShare.objects.filter(track=track, shared_with=user).exists()
    return False


def can_user_resolve_group_internal_share(user, group) -> bool:
    """Use group sharing rules as the authorization source for internal links."""
    if not getattr(user, "is_authenticated", False):
        return False
    if group.user_id == user.id:
        return True
    if group.visibility == VISIBILITY_PUBLIC:
        return True
    if group.visibility == VISIBILITY_SHARED:
        return LiveTrackGroupShare.objects.filter(group=group, shared_with=user).exists()
    return False


def _new_internal_share_id() -> str:
    while True:
        share_id = str(uuid.uuid4())
        track_exists = LiveTrackInternalShare.objects.filter(share_id=share_id).exists()
        group_exists = LiveTrackGroupInternalShare.objects.filter(share_id=share_id).exists()
        if not track_exists and not group_exists:
            return share_id
