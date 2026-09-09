"""
Shared helpers for live_track extension (parsing plus kernel adapters).
"""

import json
import re
import secrets
import types
from urllib.parse import parse_qs

from api.utils.responses import error_response

import diceware

from geo_lib.sharing.access_policy import AccessRole
from geo_lib.track.payload import (
    DEFAULT_TRACK_COLOR,
    TrackPayloadMode,
    color_from_settings,
    normalize_track_settings_for_api,
    strip_ser_from_params,
)
from geo_lib.track.points import latest_coord_by_time, timestamp_to_ms
from geo_lib.track.window import filter_coords_by_recent_window

from .access import (
    accepted_group_ids_for_user,
    accepted_group_track_ids_for_user,
    can_user_see_track,
    can_user_see_track_via_accepted_group_share,
    can_user_see_track_via_group_share,
    can_user_see_track_via_owned_group_membership,
)
from .aggregate import TrackAggregate
from .models import (
    LiveTrack,
    LiveTrackGroup,
    LiveTrackGroupMember,
    VISIBILITY_SHARED,
)
from .realtime import (
    LIVE_TRACK_FLUSH_DELAY_SECONDS,
    LIVE_TRACK_FLUSH_SCHEDULE_KEY,
    LIVE_TRACK_FLUSH_TASK_NAME,
    LIVE_TRACK_FLUSHER_ALIVE_KEY,
    LIVE_TRACK_PENDING_PREFIX,
    broadcast_track_updated,
    flush_pending_broadcasts,
    flush_pending_broadcasts_task,
    get_flusher_alive_timestamp,
    is_flusher_alive,
    queue_broadcast_track_updated,
    set_flusher_alive,
)


def generate_hauk_password() -> str:
    """Generate a per-tracker Hauk password in word.word.1234 style (e.g. banana.fork.1234)."""
    opts = types.SimpleNamespace(
        num=2,
        delimiter=".",
        specials=0,
        caps=False,
        randomsource="system",
        infile=None,
        wordlist=["en_eff"],
        verbose=0,
        dice_sides=6,
    )
    phrase = diceware.get_passphrase(opts)
    return f"{phrase}.{secrets.randbelow(10000):04d}"


def _timestamp_to_ms(ts) -> int | None:
    return timestamp_to_ms(ts)


def _filter_coords_by_recent_window(coords, point_params, window_key: str):
    return filter_coords_by_recent_window(coords, point_params, window_key)


def _color_from_settings(track: LiveTrack) -> str:
    return color_from_settings(getattr(track, "settings", None))


def _strip_ser_from_params(point_params: list) -> None:
    strip_ser_from_params(point_params)


def _role_for_response(*, is_owner: bool, for_world_share: bool) -> AccessRole:
    if for_world_share:
        return AccessRole.WORLD
    if is_owner:
        return AccessRole.OWNER
    return AccessRole.DIRECT_SHAREE


def track_to_response(
    track: LiveTrack,
    include_secret: bool = False,
    all_data: bool = False,
    is_owner: bool = True,
    for_world_share: bool = False,
) -> dict:
    role = _role_for_response(is_owner=is_owner, for_world_share=for_world_share)
    mode = TrackPayloadMode.FULL if all_data else TrackPayloadMode.GEOMETRY
    return TrackAggregate(track).payload(mode, role, include_secret=include_secret)


def track_to_response_metadata_only(
    track: LiveTrack, include_secret: bool = False, is_owner: bool = True
) -> dict:
    role = AccessRole.OWNER if is_owner else AccessRole.DIRECT_SHAREE
    return TrackAggregate(track).payload(TrackPayloadMode.DETAIL, role, include_secret=include_secret)


def visible_group_track_ids_for_user(
    group: LiveTrackGroup,
    user,
    is_owner: bool,
    is_accepted: bool,
) -> list[str]:
    member_track_ids = list(
        LiveTrackGroupMember.objects.filter(group=group).values_list("track_id", flat=True)
    )
    if not member_track_ids:
        return []
    if is_owner:
        return [str(track_id) for track_id in member_track_ids]
    if group.visibility == VISIBILITY_SHARED and not is_accepted:
        return []

    tracks = LiveTrack.objects.filter(id__in=member_track_ids).select_related("user")
    visible_ids: list[str] = []
    for track in tracks:
        if can_user_see_track(user, track) or can_user_see_track_via_accepted_group_share(user, track):
            visible_ids.append(str(track.id))
    return visible_ids


_CHARSET_ALIASES = {
    "iso-8859-1": "latin-1",
    "iso_8859-1": "latin-1",
    "latin1": "latin-1",
    "utf-8": "utf-8",
    "utf8": "utf-8",
}


def get_json_body(request):
    """Parse request body JSON and return (data, err_response)."""
    try:
        data = json.loads(request.body) if request.body else {}
        return data, None
    except json.JSONDecodeError:
        return None, error_response("Invalid JSON", 400)


def _decode_request_body(raw: bytes, content_type: str) -> str:
    charset = None
    if content_type:
        match = re.search(r"charset\s*=\s*([^\s;]+)", content_type, re.IGNORECASE)
        if match:
            charset = match.group(1).strip(" \t\"'").lower()
            charset = _CHARSET_ALIASES.get(charset, charset)
    for encoding in (charset or "utf-8", "utf-8", "latin-1"):
        if not encoding:
            continue
        try:
            return raw.decode(encoding)
        except (LookupError, UnicodeDecodeError):
            continue
    return raw.decode("latin-1")


def parse_ingress_body(request) -> dict:
    """Parse POST body as form or JSON into a flat dict for Pydantic."""
    content_type = (request.META.get("CONTENT_TYPE") or request.content_type or "").strip()
    ct = content_type.split(";")[0].strip().lower()
    if ct == "application/json":
        try:
            body_str = _decode_request_body(request.body, content_type)
            return json.loads(body_str)
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {}
    body = _decode_request_body(request.body, content_type)
    parsed = parse_qs(body, keep_blank_values=True)
    return {k: (v[0] if len(v) == 1 else v) for k, v in parsed.items()}


def parse_time_to_ms(body: dict) -> int | None:
    """Parse time from body: 'timestamp' (epoch sec or ms) -> Unix ms. If < 1e12 treat as seconds."""
    return timestamp_to_ms(body.get("timestamp"))
