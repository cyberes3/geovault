"""
Shared helpers for live_track extension (response building, parsing, broadcast).
"""

import copy
import json
import time
from urllib.parse import parse_qs

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from geo_lib.utils.redis_connection import get_redis_connection

from .models import (
    LiveTrack,
    LiveTrackGroupMember,
    LiveTrackShare,
    LiveTrackSubscription,
    VISIBILITY_PUBLIC,
    VISIBILITY_SHARED,
)


RECENT_WINDOW_MS = {
    "1min": 60 * 1000,
    "1h": 3600 * 1000,
    "1d": 24 * 3600 * 1000,
    "1w": 7 * 24 * 3600 * 1000,
    "1m": 30 * 24 * 3600 * 1000,
}


def _timestamp_to_ms(ts) -> int | None:
    """Normalize coordinate index-2 timestamp to milliseconds."""
    if ts is None:
        return None
    try:
        val = int(ts)
        if val < 1e12:
            return val * 1000
        return val
    except (TypeError, ValueError):
        return None


def _filter_coords_by_recent_window(coords, point_params, window_key: str):
    """Keep only coords (and matching point_params) with timestamp >= (now - window)."""
    if window_key not in RECENT_WINDOW_MS:
        return coords, point_params
    cutoff_ms = int(time.time() * 1000) - RECENT_WINDOW_MS[window_key]
    n = len(coords)
    if n != len(point_params):
        return coords, point_params
    kept_coords = []
    kept_params = []
    for i, c in enumerate(coords):
        if len(c) >= 3:
            ts_ms = _timestamp_to_ms(c[2])
            if ts_ms is not None and ts_ms >= cutoff_ms:
                kept_coords.append(c)
                kept_params.append(point_params[i])
        else:
            kept_coords.append(c)
            kept_params.append(point_params[i])
    return kept_coords, kept_params


def _color_from_settings(track: LiveTrack) -> str:
    return (track.settings or {}).get("color") or "#3388ff"


def _strip_ser_from_params(point_params: list) -> None:
    """Remove 'ser' (serial) from each param dict in place; never share with third parties."""
    for p in point_params:
        if isinstance(p, dict) and "ser" in p:
            p.pop("ser", None)


def track_to_response(
    track: LiveTrack,
    include_secret: bool = False,
    all_data: bool = False,
    is_owner: bool = True,
) -> dict:
    geom = copy.deepcopy(track.geometry or {"type": "LineString", "coordinates": []})
    point_params = copy.deepcopy(track.point_params or [])
    coords = geom.get("coordinates") or []
    window_key = None if all_data else (track.settings or {}).get("recent_data_window")
    if window_key:
        coords, point_params = _filter_coords_by_recent_window(coords, point_params, window_key)
        geom = {"type": "LineString", "coordinates": copy.deepcopy(coords)}
    if not is_owner:
        if not getattr(track, "share_params_with_recipients", False):
            point_params = []
        else:
            _strip_ser_from_params(point_params)
    for p in point_params:
        if "acc" in p and isinstance(p["acc"], (int, float)):
            p["acc"] = round(float(p["acc"]), 1)
        if "alt" in p and isinstance(p["alt"], (int, float)):
            p["alt"] = int(round(float(p["alt"])))
        for k, v in list(p.items()):
            if "timestamp" in k.lower() and isinstance(v, (int, float)):
                if v > 1e11:
                    p[k] = int(round(v / 1000.0))
                else:
                    p[k] = int(round(v))

    coords = geom.get("coordinates") or []
    rounded_coords = []
    for c in coords:
        if len(c) >= 2:
            pt = [round(c[0], 5), round(c[1], 5)]
            if len(c) >= 3 and isinstance(c[2], (int, float)):
                pt.append(int(round(c[2])))
            elif len(c) >= 3:
                pt.append(c[2])
            if len(c) > 3:
                pt.extend(c[3:])
            rounded_coords.append(pt)
        else:
            rounded_coords.append(c)
    geom["coordinates"] = rounded_coords
    
    bbox = None
    if rounded_coords:
        lons = [c[0] for c in rounded_coords]
        lats = [c[1] for c in rounded_coords]
        bbox = [round(min(lons), 5), round(min(lats), 5), round(max(lons), 5), round(max(lats), 5)]
    
    out = {
        "id": str(track.id),
        "name": track.name,
        "color": _color_from_settings(track),
        "geometry": geom,
        "point_params": point_params,
        "bbox": bbox,
        "settings": track.settings or {},
        "visibility": getattr(track, "visibility", "private"),
        "share_params_with_recipients": getattr(track, "share_params_with_recipients", False),
        "created_at": int(track.created_at.timestamp()) if track.created_at else None,
        "updated_at": int(track.updated_at.timestamp()) if track.updated_at else None,
    }
    if include_secret and is_owner:
        out["tracker_secret"] = track.tracker_secret
    if is_owner:
        emails = list(
            LiveTrackShare.objects.filter(track=track)
            .values_list("shared_with__email", flat=True)
        )
        out["shared_with_emails"] = [e for e in emails if e]
    return out


def track_to_response_metadata_only(
    track: LiveTrack, include_secret: bool = False, is_owner: bool = True
) -> dict:
    """Tracker metadata + latest point params only (no full geometry). For GET trackers/<id>/."""
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = geom.get("coordinates") or []
    point_params = list(track.point_params or [])

    window_key = (track.settings or {}).get("recent_data_window")
    if window_key:
        coords, point_params = _filter_coords_by_recent_window(coords, point_params, window_key)

    latest_params = [copy.deepcopy(point_params[-1])] if point_params else []
    if not is_owner:
        if not getattr(track, "share_params_with_recipients", False):
            latest_params = []
        else:
            _strip_ser_from_params(latest_params)
    for p in latest_params:
        if "acc" in p and isinstance(p["acc"], (int, float)):
            p["acc"] = round(float(p["acc"]), 1)
        if "alt" in p and isinstance(p["alt"], (int, float)):
            p["alt"] = int(round(float(p["alt"])))
        for k, v in list(p.items()):
            if "timestamp" in k.lower() and isinstance(v, (int, float)):
                if v > 1e11:
                    p[k] = int(round(v / 1000.0))
                else:
                    p[k] = int(round(v))
    last_point = None
    if coords:
        lp = coords[-1]
        last_point = [round(lp[0], 5), round(lp[1], 5)]
        if len(lp) >= 3 and isinstance(lp[2], (int, float)):
            last_point.append(int(round(lp[2])))
        elif len(lp) >= 3:
            last_point.append(lp[2])
        if len(lp) > 3:
            last_point.extend(lp[3:])
    
    bbox = None
    if coords:
        lons = [c[0] for c in coords]
        lats = [c[1] for c in coords]
        bbox = [round(min(lons), 5), round(min(lats), 5), round(max(lons), 5), round(max(lats), 5)]
        
    out = {
        "id": str(track.id),
        "name": track.name,
        "color": _color_from_settings(track),
        "point_params": latest_params,
        "bbox": bbox,
        "settings": track.settings or {},
        "visibility": getattr(track, "visibility", "private"),
        "share_params_with_recipients": getattr(track, "share_params_with_recipients", False),
        "created_at": int(track.created_at.timestamp()) if track.created_at else None,
        "updated_at": int(track.updated_at.timestamp()) if track.updated_at else None,
        "last_point": last_point,
    }
    if include_secret and is_owner:
        out["tracker_secret"] = track.tracker_secret
    if is_owner:
        emails = list(
            LiveTrackShare.objects.filter(track=track)
            .values_list("shared_with__email", flat=True)
        )
        out["shared_with_emails"] = [e for e in emails if e]
    return out


def can_user_see_track(user, track: LiveTrack) -> bool:
    """True if user is owner or track is visible to them (public = all auth users, or shared with user)."""
    if track.user_id == user.id:
        return True
    if track.visibility == VISIBILITY_PUBLIC:
        return True
    if track.visibility == VISIBILITY_SHARED:
        return LiveTrackShare.objects.filter(track=track, shared_with=user).exists()
    return False


def can_user_see_track_via_group(user, track: LiveTrack) -> bool:
    """True if user is a member of a group that contains this track (so they can view shared-group trackers)."""
    return LiveTrackGroupMember.objects.filter(
        track=track, group__user_members__user=user
    ).exists()


def parse_ingress_body(request) -> dict:
    """Parse POST body as form or JSON into a flat dict for Pydantic."""
    ct = (request.content_type or "").split(";")[0].strip().lower()
    if ct == "application/json":
        try:
            return json.loads(request.body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {}
    body = request.body.decode("utf-8")
    parsed = parse_qs(body, keep_blank_values=True)
    return {k: (v[0] if len(v) == 1 else v) for k, v in parsed.items()}


def parse_time_to_ms(body: dict) -> int | None:
    """Parse time from body: 'timestamp' (epoch sec or ms) -> Unix ms. If < 1e12 treat as seconds."""
    ts = body.get("timestamp")
    if ts is not None:
        try:
            val = int(ts)
            if val < 1e12:
                return val * 1000
            return val
        except (ValueError, TypeError):
            pass
    return None


def _props_for_subscriber(props: dict, share_params: bool) -> dict:
    """For subscribers: omit props if not share_params; always strip ser."""
    if not share_params:
        return {}
    out = dict(props) if props else {}
    out.pop("ser", None)
    return out


def broadcast_track_updated(
    track: LiveTrack,
    point: list,
    props: dict,
    index: int | None = None,
):
    """Broadcast new point to owner and all subscribers. Owner gets full props; subscribers get props only if share_params_with_recipients and never get ser."""
    channel_layer = get_channel_layer()
    if not channel_layer:
        return
    track_id = str(track.id)
    owner_id = track.user_id
    share_params = getattr(track, "share_params_with_recipients", False)
    subscriber_ids = list(
        LiveTrackSubscription.objects.filter(track=track)
        .exclude(user_id=owner_id)
        .values_list("user_id", flat=True)
    )
    # Owner payload (full)
    owner_data = {"track_id": track_id, "point": point, "props": props or {}}
    if index is not None:
        owner_data["index"] = index
    msg_owner = {"type": "live_track_track_updated", "data": owner_data}
    async_to_sync(channel_layer.group_send)(f"live_track_{owner_id}", msg_owner)
    # Subscriber payload (filtered)
    sub_props = _props_for_subscriber(props, share_params)
    sub_data = {"track_id": track_id, "point": point, "props": sub_props}
    if index is not None:
        sub_data["index"] = index
    msg_sub = {"type": "live_track_track_updated", "data": sub_data}
    for uid in subscriber_ids:
        async_to_sync(channel_layer.group_send)(f"live_track_{uid}", msg_sub)


LIVE_TRACK_PENDING_PREFIX = "live_track_pending:"


def queue_broadcast_track_updated(
    track: LiveTrack,
    point: list,
    props: dict,
    index: int | None = None,
) -> bool:
    """Append an update to the Redis buffer for this track. Returns True if queued, False if Redis unavailable."""
    try:
        redis_client = get_redis_connection()
    except Exception:
        return False
    track_id = str(track.id)
    owner_id = track.user_id
    share_params = getattr(track, "share_params_with_recipients", False)
    subscriber_ids = list(
        LiveTrackSubscription.objects.filter(track=track)
        .exclude(user_id=owner_id)
        .values_list("user_id", flat=True)
    )
    payload = {
        "track_id": track_id,
        "owner_id": owner_id,
        "subscriber_ids": subscriber_ids,
        "share_params_with_recipients": share_params,
        "point": point,
        "props": props or {},
        "index": index,
    }
    key = f"{LIVE_TRACK_PENDING_PREFIX}{track_id}"
    redis_client.rpush(key, json.dumps(payload))
    return True


def flush_pending_broadcasts() -> int:
    """Read all pending updates from Redis, send one batched message per (user, track), delete keys. Returns number of tracks flushed."""
    try:
        redis_client = get_redis_connection()
    except Exception:
        return 0
    keys = redis_client.keys(f"{LIVE_TRACK_PENDING_PREFIX}*")
    if not keys:
        return 0
    channel_layer = get_channel_layer()
    if not channel_layer:
        for key in keys:
            redis_client.delete(key)
        return 0
    flushed = 0
    for key in keys:
        raw_list = redis_client.lrange(key, 0, -1)
        redis_client.delete(key)
        if not raw_list:
            continue
        track_id = (key.decode() if isinstance(key, bytes) else key).replace(
            LIVE_TRACK_PENDING_PREFIX, "", 1
        )
        updates_by_user = {}  # user_id -> list of { point, props, index }
        for raw in raw_list:
            try:
                raw_str = raw.decode() if isinstance(raw, bytes) else raw
                item = json.loads(raw_str)
            except (json.JSONDecodeError, TypeError):
                continue
            owner_id = item.get("owner_id")
            subscriber_ids = item.get("subscriber_ids") or []
            share_params = item.get("share_params_with_recipients", False)
            point = item.get("point") or []
            props = item.get("props") or {}
            idx = item.get("index")
            owner_update = {"point": point, "props": props, "index": idx}
            sub_update = {"point": point, "props": _props_for_subscriber(props, share_params), "index": idx}
            updates_by_user.setdefault(owner_id, []).append(owner_update)
            for uid in subscriber_ids:
                updates_by_user.setdefault(uid, []).append(sub_update)
        for user_id, updates in updates_by_user.items():
            data = {"track_id": track_id, "updates": updates}
            message = {"type": "live_track_track_updated", "data": data}
            async_to_sync(channel_layer.group_send)(f"live_track_{user_id}", message)
        flushed += 1
    return flushed
