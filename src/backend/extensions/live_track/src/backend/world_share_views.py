"""
World (unauthenticated) share endpoints for tracker share links.

Deterministic lookup contract: when resolving a world-share ID, track is looked up
first, then group. If both existed with the same ID (collision), track takes precedence.
"""

import re

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.views.sharing.utils import build_client_share_url

from .helpers import track_to_response
from .internal_share_links import resolve_internal_share_info
from .models import LiveTrack, LiveTrackGroupMember, LiveTrackGroupWorldShare, LiveTrackWorldShare
from .validation import ShareDiscoveryResponse

_SHARE_HASH_PATH = "/#/extensions/live-track/share?id="


def _validate_share_id(share_id: str) -> bool:
    """Validate share_id is UUID4 format."""
    if not share_id or not isinstance(share_id, str):
        return False
    pattern = r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    return bool(re.match(pattern, share_id.lower()))


def build_live_track_share_url(request, share_id: str) -> str:
    """Return the client-resolved URL for the world share."""
    return build_client_share_url(f"{_SHARE_HASH_PATH}{share_id}")


def build_live_track_group_share_url(request, share_id: str) -> str:
    """Return the client-resolved URL for the group world share."""
    return build_client_share_url(f"{_SHARE_HASH_PATH}{share_id}")


def resolve_live_track_share_info(share_id: str, user) -> dict | None:
    """Resolve standalone share metadata across world and internal live-track shares."""
    if not _validate_share_id(share_id):
        return None

    track_share = LiveTrackWorldShare.objects.filter(share_id=share_id).select_related("track").first()
    if track_share:
        track = track_share.track
        return {
            "share_access": "world",
            "share_type": "live_track",
            "track_id": str(track.id),
            "track_name": track.name,
            "created_at": track_share.created_at.isoformat(),
        }

    group_share = LiveTrackGroupWorldShare.objects.filter(share_id=share_id).select_related("group").first()
    if group_share:
        group = group_share.group
        return {
            "share_access": "world",
            "share_type": "live_track_group",
            "group_id": str(group.id),
            "group_name": group.name,
            "created_at": group_share.created_at.isoformat(),
        }

    internal_payload = resolve_internal_share_info(share_id, user)
    if internal_payload:
        return {
            **internal_payload,
            "share_access": "internal",
        }

    return None


@require_http_methods(["GET"])
def share_info(request, share_id):
    """GET share/<share_id>/info/ - standalone share discovery for world and internal links."""
    payload = resolve_live_track_share_info(share_id, request.user)
    if payload is None:
        return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)
    response = ShareDiscoveryResponse.model_validate(payload)
    return JsonResponse(response.model_dump(exclude_none=True))


@require_http_methods(["GET"])
def world_share_info(request, share_id):
    """
    GET world/share/<share_id>/info/ — world share, no auth.
    Returns share_type (live_track or live_track_group), and type-specific fields.
    Lookup order: track first, then group.
    """
    if not _validate_share_id(share_id):
        return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)
    track_share = LiveTrackWorldShare.objects.filter(share_id=share_id).select_related("track").first()
    if track_share:
        track = track_share.track
        return JsonResponse({
            "share_type": "live_track",
            "track_id": str(track.id),
            "track_name": track.name,
            "created_at": track_share.created_at.isoformat(),
        })
    group_share = LiveTrackGroupWorldShare.objects.filter(share_id=share_id).select_related("group").first()
    if group_share:
        group = group_share.group
        return JsonResponse({
            "share_type": "live_track_group",
            "group_id": str(group.id),
            "group_name": group.name,
            "created_at": group_share.created_at.isoformat(),
        })
    return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)


@require_http_methods(["GET"])
def world_share_data(request, share_id):
    """
    GET world/share/<share_id>/ — world share, no auth.
    Returns track payload (live_track) or group payload with tracks array (live_track_group).
    Lookup order: track first, then group.
    """
    if not _validate_share_id(share_id):
        return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)
    track_share = LiveTrackWorldShare.objects.filter(share_id=share_id).select_related("track").first()
    if track_share:
        track = track_share.track
        payload = track_to_response(
            track, include_secret=False, is_owner=False, all_data=False, for_world_share=True
        )
        return JsonResponse(payload)
    group_share = LiveTrackGroupWorldShare.objects.filter(share_id=share_id).select_related("group").first()
    if group_share:
        group = group_share.group
        track_ids = list(
            LiveTrackGroupMember.objects.filter(group=group).values_list("track_id", flat=True)
        )
        tracks = list(LiveTrack.objects.filter(id__in=track_ids).order_by("name"))
        track_payloads = [
            track_to_response(
                t, include_secret=False, is_owner=False, all_data=False, for_world_share=True
            )
            for t in tracks
        ]
        return JsonResponse({
            "share_type": "live_track_group",
            "group_name": group.name,
            "tracks": track_payloads,
        })
    return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)
