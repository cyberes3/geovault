"""TrackPayload modes: LIST / DETAIL / GEOMETRY / FULL."""

from enum import StrEnum
from typing import Any

from geo_lib.sharing.access_policy import AUTH_VIEWER_ROLES, AccessRole
from geo_lib.track.points import timestamp_to_ms
from geo_lib.track.store import PointStore, last_point_from_store
from geo_lib.track.window import WINDOW_ALL, normalize_recent_data_window


class TrackPayloadMode(StrEnum):
    LIST = "list"
    DETAIL = "detail"
    GEOMETRY = "geometry"
    FULL = "full"


DEFAULT_TRACK_COLOR = "#6C93DE"

WORLD_ALLOW = frozenset({"id", "name", "color", "geometry", "point_params", "bbox", "geometry_status"})
AUTH_STRIP = frozenset(
    {
        "tracker_secret",
        "hauk_password",
        "shared_with_emails",
        "share_params_with_world",
        "subscriber_count",
        "subscribers",
    }
)


def color_from_settings(settings: dict | None) -> str:
    return (settings or {}).get("color") or DEFAULT_TRACK_COLOR


def normalize_track_settings_for_api(settings: dict | None) -> dict:
    s = dict(settings or {})
    s.pop("hidden_in_list", None)
    window = s.get("recent_data_window")
    if window == "":
        s["recent_data_window"] = WINDOW_ALL
    return s


def strip_ser_from_params(point_params: list) -> None:
    for p in point_params:
        if isinstance(p, dict) and "ser" in p:
            p.pop("ser", None)


def normalize_point_params_for_response(point_params: list) -> list:
    out = []
    for raw in point_params:
        p = dict(raw) if isinstance(raw, dict) else {}
        if "acc" in p and isinstance(p["acc"], (int, float)):
            p["acc"] = round(float(p["acc"]), 1)
        if "alt" in p and isinstance(p["alt"], (int, float)):
            p["alt"] = int(round(float(p["alt"])))
        for k, v in list(p.items()):
            if not isinstance(v, (int, float)):
                continue
            key = k.lower()
            if key == "starttimestamp":
                p[k] = int(round(v))
            elif "timestamp" in key:
                if v > 1e11:
                    p[k] = int(round(v / 1000.0))
                else:
                    p[k] = int(round(v))
        out.append(p)
    return out


def normalize_coords_for_response(coords: list) -> list:
    rounded = []
    for c in coords:
        if len(c) >= 2:
            pt = [round(float(c[0]), 5), round(float(c[1]), 5)]
            ts = timestamp_to_ms(c[2]) if len(c) >= 3 else None
            if ts is not None:
                pt.append(int(ts))
            elif len(c) >= 3:
                pt.append(c[2])
            if len(c) > 3:
                pt.extend(c[3:])
            rounded.append(pt)
        else:
            rounded.append(c)
    return rounded


def bbox_from_coords(coords: list) -> list | None:
    if not coords:
        return None
    lons = [c[0] for c in coords if c and len(c) >= 2]
    lats = [c[1] for c in coords if c and len(c) >= 2]
    if not lons or not lats:
        return None
    return [round(min(lons), 5), round(min(lats), 5), round(max(lons), 5), round(max(lats), 5)]


def _apply_params_visibility(params: list, role: AccessRole, share_recipients: bool, share_world: bool) -> list:
    if role == AccessRole.OWNER:
        return normalize_point_params_for_response(params)
    show = share_world if role == AccessRole.WORLD else share_recipients
    if not show:
        return []
    visible = [dict(p) if isinstance(p, dict) else {} for p in params]
    strip_ser_from_params(visible)
    return normalize_point_params_for_response(visible)


def build_track_payload(
    track,
    mode: TrackPayloadMode,
    role: AccessRole,
    *,
    store: PointStore | None = None,
    include_secret: bool = False,
    window_key: str | None = None,
    shared_with_emails: list[str] | None = None,
    geometry_status: dict | None = None,
    coords_override: list | None = None,
    params_override: list | None = None,
) -> dict[str, Any]:
    settings = normalize_track_settings_for_api(getattr(track, "settings", None))
    try:
        resolved_window = normalize_recent_data_window(
            window_key if window_key is not None else settings.get("recent_data_window")
        )
    except ValueError:
        resolved_window = WINDOW_ALL

    apply_window = mode != TrackPayloadMode.FULL and resolved_window != WINDOW_ALL
    metadata_only = mode in (TrackPayloadMode.LIST, TrackPayloadMode.DETAIL) and coords_override is None
    if metadata_only:
        latest = PointStore.latest_from_track(track, resolved_window if apply_window else None)
        last_point = None
        if latest is not None:
            last_point = [round(float(latest.lon), 5), round(float(latest.lat), 5), int(latest.timestamp_ms)]
        visible_params = _apply_params_visibility(
            [latest.params] if latest is not None else [],
            role,
            bool(getattr(track, "share_params_with_recipients", False)),
            bool(getattr(track, "share_params_with_world", False)),
        )
        rounded_coords = []
        bbox = [last_point[0], last_point[1], last_point[0], last_point[1]] if last_point else None
    else:
        store = store or PointStore.from_track(track)
        if coords_override is not None:
            coords = coords_override
            params = params_override if params_override is not None else []
        elif apply_window:
            coords, params = store.window(resolved_window)
        else:
            coords = [list(c) for c in store.coordinates]
            params = [dict(p) for p in store.params]

        rounded_coords = normalize_coords_for_response(coords)
        visible_params = _apply_params_visibility(
            params,
            role,
            bool(getattr(track, "share_params_with_recipients", False)),
            bool(getattr(track, "share_params_with_world", False)),
        )
        if visible_params and len(visible_params) != len(rounded_coords):
            visible_params = []
        last_point = last_point_from_store(store, resolved_window if apply_window else None)
        bbox = bbox_from_coords(rounded_coords)
        if bbox is None and last_point and len(last_point) >= 2:
            bbox = [last_point[0], last_point[1], last_point[0], last_point[1]]

    created_at = getattr(track, "created_at", None)
    updated_at = getattr(track, "updated_at", None)
    out: dict[str, Any] = {
        "id": str(track.id),
        "name": track.name,
        "color": color_from_settings(settings),
        "bbox": bbox,
        "settings": settings,
        "visibility": getattr(track, "visibility", "private"),
        "share_params_with_recipients": getattr(track, "share_params_with_recipients", False),
        "share_params_with_world": getattr(track, "share_params_with_world", False),
        "is_owner": role == AccessRole.OWNER,
        "created_at": int(created_at.timestamp()) if created_at else None,
        "updated_at": int(updated_at.timestamp()) if updated_at else None,
        "last_point": last_point,
        "point_params": visible_params,
    }

    if mode in (TrackPayloadMode.GEOMETRY, TrackPayloadMode.FULL):
        out["geometry"] = {"type": "LineString", "coordinates": rounded_coords}
        out["point_params"] = visible_params
        if geometry_status is not None:
            out["geometry_status"] = geometry_status

    if role == AccessRole.OWNER:
        if include_secret:
            out["tracker_secret"] = track.tracker_secret
        if getattr(track, "hauk_password", None):
            out["hauk_password"] = track.hauk_password
        if shared_with_emails is not None:
            out["shared_with_emails"] = [e for e in shared_with_emails if e]
    elif role in AUTH_VIEWER_ROLES:
        owner = getattr(track, "user", None)
        out["owner_email"] = ((getattr(owner, "email", "") or "") if owner else "").strip()

    return redact_track_payload(out, role)


def redact_track_payload(payload: dict[str, Any], role: AccessRole) -> dict[str, Any]:
    out = dict(payload)
    if role == AccessRole.NONE:
        return {"id": out.get("id")}
    if role == AccessRole.WORLD:
        return {k: out[k] for k in WORLD_ALLOW if k in out}
    if role in AUTH_VIEWER_ROLES:
        for key in AUTH_STRIP:
            out.pop(key, None)
        return out
    return out
