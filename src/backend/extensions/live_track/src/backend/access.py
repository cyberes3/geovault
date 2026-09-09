"""Tracker access on the share-kernel AccessRole / ShareLink / ShareGrant types."""

from typing import Any

from django.contrib.auth.models import AnonymousUser

from api.sharing.grants import ShareGrantService
from api.sharing.models import ShareGrant, ShareLink
from geo_lib.sharing.access_policy import AccessRole
from geo_lib.sharing.constants import AUDIENCE_WORLD, KIND_LIVE_TRACK, KIND_LIVE_TRACK_GROUP
from geo_lib.track.payload import redact_track_payload

from .models import (
    LiveTrack,
    LiveTrackGroupMember,
    LiveTrackGroupSubscription,
    VISIBILITY_PUBLIC,
    VISIBILITY_SHARED,
)


class TrackerAccessPolicy:
    def __init__(self, track: LiveTrack):
        self.track = track

    def resolve(self, user, *, world_link: bool = False) -> AccessRole:
        if world_link:
            if self.has_world_link():
                return AccessRole.WORLD
            return AccessRole.NONE
        if user is None or isinstance(user, AnonymousUser) or not getattr(user, "is_authenticated", False):
            return AccessRole.NONE
        if self.track.user_id == user.id:
            return AccessRole.OWNER
        if self.track.visibility == VISIBILITY_PUBLIC:
            return AccessRole.AUTH_PUBLIC
        if self.track.visibility == VISIBILITY_SHARED and ShareGrantService.has_grant(
            KIND_LIVE_TRACK, self.track.id, user
        ):
            return AccessRole.DIRECT_SHAREE
        if can_user_see_track_via_accepted_group_share(user, self.track):
            return AccessRole.GROUP_SHAREE
        return AccessRole.NONE

    def has_world_link(self) -> bool:
        return ShareLink.objects.active().for_track(self.track.id).world().exists()

    def can_discover(self, role: AccessRole) -> bool:
        return role != AccessRole.NONE

    def can_read_data(self, role: AccessRole) -> bool:
        if role == AccessRole.NONE:
            return False
        if role == AccessRole.WORLD:
            return self.has_world_link()
        return True

    def can_read_full_history(self, role: AccessRole) -> bool:
        return role == AccessRole.OWNER

    def redact(self, payload: dict[str, Any], role: AccessRole) -> dict[str, Any]:
        return redact_track_payload(payload, role)


def can_user_see_track(user, track: LiveTrack) -> bool:
    role = TrackerAccessPolicy(track).resolve(user)
    return role in {AccessRole.OWNER, AccessRole.DIRECT_SHAREE, AccessRole.AUTH_PUBLIC}


def _granted_group_ids_for_user(user) -> set[str]:
    return ShareGrantService.resource_ids_for_user(KIND_LIVE_TRACK_GROUP, user)


def can_user_see_track_via_group_share(user, track: LiveTrack) -> bool:
    granted = _granted_group_ids_for_user(user)
    if not granted:
        return False
    return LiveTrackGroupMember.objects.filter(
        track=track,
        group__visibility=VISIBILITY_SHARED,
        group_id__in=granted,
    ).exists()


def can_user_see_track_via_accepted_group_share(user, track: LiveTrack) -> bool:
    granted = _granted_group_ids_for_user(user)
    if not granted:
        return False
    return LiveTrackGroupMember.objects.filter(
        track=track,
        group__visibility=VISIBILITY_SHARED,
        group_id__in=granted,
        group__accepted_subscriptions__user=user,
    ).exists()


def can_user_see_track_via_owned_group_membership(user, track: LiveTrack) -> bool:
    return LiveTrackGroupMember.objects.filter(
        track=track,
        group__user=user,
    ).exists()


def accepted_group_ids_for_user(user) -> set:
    return set(
        LiveTrackGroupSubscription.objects.filter(user=user).values_list("group_id", flat=True)
    )


def accepted_group_track_ids_for_user(user) -> set:
    granted = _granted_group_ids_for_user(user)
    if not granted:
        return set()
    return set(
        LiveTrackGroupMember.objects.filter(
            group__visibility=VISIBILITY_SHARED,
            group_id__in=granted,
            group__accepted_subscriptions__user=user,
        ).values_list("track_id", flat=True)
    )


def accepted_group_viewer_ids_for_track(track: LiveTrack) -> set[int]:
    group_ids = list(
        LiveTrackGroupMember.objects.filter(
            track=track,
            group__visibility=VISIBILITY_SHARED,
        ).values_list("group_id", flat=True)
    )
    if not group_ids:
        return set()
    granted = set(
        ShareGrant.objects.filter(
            resource_kind=KIND_LIVE_TRACK_GROUP,
            resource_id__in=[str(group_id) for group_id in group_ids],
        ).values_list("resource_id", "grantee_user_id")
    )
    viewer_ids: set[int] = set()
    for user_id, group_id in (
        LiveTrackGroupSubscription.objects.filter(group_id__in=group_ids)
        .exclude(user_id=track.user_id)
        .values_list("user_id", "group_id")
    ):
        if (str(group_id), user_id) in granted:
            viewer_ids.add(user_id)
    return viewer_ids


def world_visible_group_tracks(group) -> list[LiveTrack]:
    """Member tracks that opted into anonymous WORLD access via their own world ShareLink."""
    member_ids = [
        str(track_id)
        for track_id in LiveTrackGroupMember.objects.filter(group=group).values_list("track_id", flat=True)
    ]
    if not member_ids:
        return []
    world_refs = {
        str(ref)
        for ref in ShareLink.objects.active()
        .filter(
            subject_kind=KIND_LIVE_TRACK,
            audience=AUDIENCE_WORLD,
            subject_ref__in=member_ids,
        )
        .values_list("subject_ref", flat=True)
    }
    visible_ids = [track_id for track_id in member_ids if track_id in world_refs]
    if not visible_ids:
        return []
    return list(LiveTrack.objects.filter(id__in=visible_ids).select_related("user").order_by("name"))
