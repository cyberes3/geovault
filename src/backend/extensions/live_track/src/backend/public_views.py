"""
World (unauthenticated) share endpoints for tracker share links.
"""

import re

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from .helpers import _filter_coords_by_recent_window, track_to_response
from .models import LiveTrack, LiveTrackPublicShare


def _validate_share_id(share_id: str) -> bool:
    """Validate share_id is UUID4 format."""
    if not share_id or not isinstance(share_id, str):
        return False
    pattern = r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    return bool(re.match(pattern, share_id.lower()))


def build_live_track_share_url(share_id: str) -> str:
    """Return the frontend path for the world share (hash route)."""
    return f"/#/extensions/live-track/share?id={share_id}"


@require_http_methods(["GET"])
def public_share_info(request, share_id):
    """
    GET public/share/<share_id>/info/ — world share, no auth.
    Returns share_type, track_name, track_id, created_at.
    """
    if not _validate_share_id(share_id):
        return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)
    share = LiveTrackPublicShare.objects.filter(share_id=share_id).select_related("track").first()
    if not share:
        return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)
    track = share.track
    return JsonResponse({
        "share_type": "live_track",
        "track_id": str(track.id),
        "track_name": track.name,
        "created_at": share.created_at.isoformat(),
    })


@require_http_methods(["GET"])
def public_share_data(request, share_id):
    """
    GET public/share/<share_id>/ — world share, no auth.
    Returns track name, geometry, point_params (respecting recent_data_window) for the shared track.
    """
    if not _validate_share_id(share_id):
        return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)
    share = LiveTrackPublicShare.objects.filter(share_id=share_id).select_related("track").first()
    if not share:
        return JsonResponse({"error": "Invalid share link", "code": 404}, status=404)
    track = share.track
    payload = track_to_response(track, include_secret=False, is_owner=False, all_data=False)
    return JsonResponse(payload)
