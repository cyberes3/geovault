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
                if c[2] > 1e11:
                    pt.append(int(round(c[2] / 1000.0)))
                else:
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
        "color": track.color,
        "geometry": geom,
        "point_params": point_params,
        "bbox": bbox,
        "created_at": int(track.created_at.timestamp()) if track.created_at else None,
        "updated_at": int(track.updated_at.timestamp()) if track.updated_at else None,
    }
    if include_secret:
        out["tracker_secret"] = track.tracker_secret
    return out


def track_to_response_metadata_only(track: LiveTrack, include_secret: bool = False) -> dict:
    """Tracker metadata + latest point params only (no full geometry). For GET trackers/<id>/."""
    point_params = track.point_params or []
    latest_params = [copy.deepcopy(point_params[-1])] if point_params else []
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
    
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = geom.get("coordinates") or []
    last_point = None
    if coords:
        lp = coords[-1]
        last_point = [round(lp[0], 5), round(lp[1], 5)]
        if len(lp) >= 3 and isinstance(lp[2], (int, float)):
            if lp[2] > 1e11:
                last_point.append(int(round(lp[2] / 1000.0)))
            else:
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
        "color": track.color,
        "point_params": latest_params,
        "bbox": bbox,
        "created_at": int(track.created_at.timestamp()) if track.created_at else None,
        "updated_at": int(track.updated_at.timestamp()) if track.updated_at else None,
        "last_point": last_point,
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
    """Broadcast new point so client can insert or append. point = [lon, lat, timestamp_ms]; props = point_params; index = insertion index when not append.
    Only sent to live_track_{user_id} (standalone trackers-live WS). Do not send to realtime_{user_id};
    RealtimeConsumer no longer has a live_track module and would raise 'No handler for message type live_track_track_updated'.
    """
    channel_layer = get_channel_layer()
    if channel_layer:
        data = {"track_id": track_id, "point": point, "props": props}
        if index is not None:
            data["index"] = index
        message = {"type": "live_track_track_updated", "data": data}
        async_to_sync(channel_layer.group_send)(f"live_track_{user_id}", message)
