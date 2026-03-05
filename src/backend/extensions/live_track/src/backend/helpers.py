"""
Shared helpers for live_track extension (response building, parsing, broadcast).
"""

import copy
import json
from urllib.parse import parse_qs

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from .models import LiveTrack


def track_to_response(track: LiveTrack, include_secret: bool = False) -> dict:
    geom = copy.deepcopy(track.geometry or {"type": "LineString", "coordinates": []})
    point_params = copy.deepcopy(track.point_params or [])
    out = {
        "id": str(track.id),
        "name": track.name,
        "color": track.color,
        "geometry": geom,
        "point_params": point_params,
        "created_at": track.created_at.isoformat() if track.created_at else None,
        "updated_at": track.updated_at.isoformat() if track.updated_at else None,
    }
    if include_secret:
        out["tracker_secret"] = track.tracker_secret
    return out


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


def broadcast_track_updated(
    user_id: int,
    track_id: str,
    point: list,
    props: dict,
    index: int | None = None,
):
    """Broadcast new point so client can insert or append. point = [lon, lat, timestamp_ms]; props = point_params; index = insertion index when not append."""
    channel_layer = get_channel_layer()
    if channel_layer:
        data = {"track_id": track_id, "point": point, "props": props}
        if index is not None:
            data["index"] = index
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {"type": "live_track_track_updated", "data": data},
        )
