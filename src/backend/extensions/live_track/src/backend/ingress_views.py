"""
Ingress endpoints: POST-only, Basic Auth or OAuth, rate limit, insert point by timestamp.
"""

import base64
import bisect
import gzip
import struct
import uuid

from django.conf import settings
from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.db import transaction
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from pydantic import ValidationError as PydanticValidationError

from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response
from api.utils.responses import handle_404
from website.config_loader import get_config_loader

from .helpers import broadcast_track_updated, parse_ingress_body, parse_time_to_ms, queue_broadcast_track_updated
from .models import LiveTrack
from .validation import LiveTrackIngressBody

User = get_user_model()
logger = get_tagged_logger()


def append_point_to_track(track, lat: float, lon: float, timestamp_ms: int, extra: dict | None = None) -> int | None:
    """
    Append one point to a track (geometry + point_params), broadcast update. Used by ingress and Hauk post.
    Returns the index of the inserted point for broadcasting, or None if not found.
    """
    max_points = get_config_loader().get_int("extensions.live_track.max_points", 1000)
    extra = extra or {}
    with transaction.atomic():
        track_locked = LiveTrack.objects.select_for_update().get(pk=track.id)
        geom = track_locked.geometry or {"type": "LineString", "coordinates": []}
        coords = list(geom.get("coordinates") or [])
        point_params = list(track_locked.point_params or [])

        new_point = [lon, lat, timestamp_ms]
        ts_list = [c[2] for c in coords]
        idx = bisect.bisect_right(ts_list, timestamp_ms)
        coords.insert(idx, new_point)
        point_params.insert(idx, dict(extra))

        if len(coords) > max_points:
            n_removed = len(coords) - max_points
            coords = coords[n_removed:]
            point_params = point_params[n_removed:]

        track_locked.geometry = {"type": "LineString", "coordinates": coords}
        track_locked.point_params = point_params
        track_locked.updated_at = timezone.now()
        track_locked.save(update_fields=["geometry", "point_params", "updated_at"])

        broadcast_idx = next((i for i, c in enumerate(coords) if c == new_point), None)

    if broadcast_idx is not None:
        if not queue_broadcast_track_updated(track, new_point, extra, index=broadcast_idx):
            broadcast_track_updated(track, new_point, extra, index=broadcast_idx)
    return broadcast_idx


def _decode_basic_auth(request):
    auth = request.META.get("HTTP_AUTHORIZATION") or ""
    if not auth.startswith("Basic "):
        return None, None
    try:
        decoded = base64.b64decode(auth[6:].strip()).decode("utf-8")
        if ":" not in decoded:
            return None, None
        username, password = decoded.split(":", 1)
        return username.strip(), password
    except (ValueError, UnicodeDecodeError):
        return None, None


def _resolve_user_and_track(email: str, password: str):
    """Resolve user by email only (user-facing identifier); internal username is not used."""
    user = User.objects.filter(email=email).first()
    if not user:
        return None, None
    track = LiveTrack.objects.filter(user=user, tracker_secret=password).first()
    return user, track


@require_http_methods(["POST"])
@csrf_exempt
def ingress(request):
    username, password = _decode_basic_auth(request)
    if not username or not password:
        return error_response("Missing or invalid Basic Auth", 401)
    user, track = _resolve_user_and_track(username, password)
    if not track:
        return error_response("Invalid credentials", 401)

    request.user = user  # So logging middleware shows identity instead of Anonymous

    cache_backend = getattr(settings, "CACHES", {}).get("default", {}).get("BACKEND", "")
    if "redis" in cache_backend.lower():
        rate_key = f"live_track_ingress:{track.id}"
        now_ts = timezone.now().timestamp()
        last = cache.get(rate_key)
        if last is not None and (now_ts - last) < 1.0:
            return error_response("Rate limit exceeded", 429)
        cache.set(rate_key, now_ts, timeout=2)

    raw = parse_ingress_body(request)
    try:
        body = LiveTrackIngressBody.model_validate(raw)
    except PydanticValidationError as e:
        errs = e.errors()
        msg = errs[0].get("msg", "Invalid body") if errs else "Invalid body"
        logger.warning("live-track ingress 400: %s (body=%s)", msg, raw)
        return error_response(msg, 400)

    timestamp_ms = parse_time_to_ms(raw)
    if timestamp_ms is None:
        timestamp_ms = int(timezone.now().timestamp() * 1000)

    extra = {k: v for k, v in body.model_dump().items() if k not in ("lat", "lon", "timestamp") and v is not None}
    append_point_to_track(track, body.lat, body.lon, timestamp_ms, extra)
    return JsonResponse({"ok": True}, status=200)


# Max string lengths for GVLT extended block (match Android encoder caps)
_MAX_PROV_BYTES = 64
_MAX_SER_BYTES = 64
_MAX_DESC_BYTES = 256
_MAX_POINTS_PER_PAYLOAD = 5000


def _parse_gvlm_minimal(body):
    """
    Parse GVLM minimal payload: magic "GVLM" (4) + uuid (16) + points.
    Each point: 17 bytes (flag, time int64, lat float32, lon float32). No extended fields.
    Returns (tracker_uuid, points, err). Points have only lat, lon, timestamp.
    """
    if not body.startswith(b"GVLM"):
        return None, None, "Invalid magic bytes"
    if len(body) < 20:
        return None, None, "Invalid magic bytes"
    try:
        tracker_uuid = uuid.UUID(bytes=bytes(body[4:20]))
    except (ValueError, TypeError):
        return None, None, "Invalid tracker ID"
    offset = 20
    points = []
    while offset < len(body):
        if len(points) >= _MAX_POINTS_PER_PAYLOAD:
            return None, None, "Too many points"
        if offset + 17 > len(body):
            return None, None, "Incomplete base point"
        _flag, ts_ms, lat, lon = struct.unpack_from(">Bqff", body, offset)
        offset += 17
        points.append({"lat": float(lat), "lon": float(lon), "timestamp": ts_ms})
    return tracker_uuid, points, None


def _parse_gvlt_points(body):
    """
    Parse GVLT binary: magic(4) + uuid(16) + batch_block(8+1+ser) then per-point.
    Per-point: base 17 bytes (flag, time, lat float32, lon float32), extended (sat, alt, spd_kph,
    bearing, acc, batt, ischarging, dist_m, prov, desc). starttimestamp and ser come from batch.
    Returns (tracker_uuid, points, err). Uses bearing only (not dir).
    """
    if not body.startswith(b"GVLT"):
        return None, None, "Invalid magic bytes"
    if len(body) < 20:
        return None, None, "Invalid magic bytes"
    try:
        tracker_uuid = uuid.UUID(bytes=bytes(body[4:20]))
    except (ValueError, TypeError):
        return None, None, "Invalid tracker ID"

    if len(body) < 29:
        return None, None, "Incomplete batch block"
    starttimestamp_ms, = struct.unpack_from(">q", body, 20)
    ser_len = body[28]
    if len(body) < 29 + ser_len:
        return None, None, "Incomplete batch block"
    ser_str = ""
    if ser_len > 0:
        read_len = min(ser_len, _MAX_SER_BYTES)
        ser_str = body[29 : 29 + read_len].decode("utf-8", errors="replace")

    offset = 29 + ser_len
    points = []
    while offset < len(body):
        if len(points) >= _MAX_POINTS_PER_PAYLOAD:
            return None, None, "Too many points"
        if offset + 17 > len(body):
            return None, None, "Incomplete base point"

        _flag, ts_ms, lat, lon = struct.unpack_from(">Bqff", body, offset)
        offset += 17

        point_data = {"lat": float(lat), "lon": float(lon), "timestamp": ts_ms}
        point_data["starttimestamp"] = starttimestamp_ms
        if ser_str:
            point_data["ser"] = ser_str

        if offset + 2 + 4 * 4 + 1 + 1 + 4 > len(body):
            return None, None, "Incomplete extended data"
        sat, = struct.unpack_from(">H", body, offset)
        offset += 2
        alt, spd_kph, bearing, acc = struct.unpack_from(">ffff", body, offset)
        offset += 16
        batt, ischarging = struct.unpack_from(">Bb", body, offset)
        offset += 2
        dist_m, = struct.unpack_from(">f", body, offset)
        offset += 4

        if sat > 0:
            point_data["sat"] = sat
        point_data["alt"] = alt
        point_data["spd_kph"] = spd_kph
        point_data["bearing"] = bearing
        point_data["acc"] = acc
        point_data["batt"] = batt
        point_data["ischarging"] = bool(ischarging)
        point_data["dist"] = dist_m

        if offset + 1 > len(body):
            return None, None, "Incomplete extended data"
        prov_len = body[offset]
        offset += 1
        if offset + prov_len > len(body):
            return None, None, "Incomplete extended data"
        if prov_len > 0:
            read_len = min(prov_len, _MAX_PROV_BYTES)
            point_data["prov"] = body[offset : offset + read_len].decode("utf-8", errors="replace")
        offset += prov_len

        if offset + 2 > len(body):
            return None, None, "Incomplete extended data"
        desc_len, = struct.unpack_from(">H", body, offset)
        offset += 2
        if offset + desc_len > len(body):
            return None, None, "Incomplete extended data"
        if desc_len > 0:
            read_len = min(desc_len, _MAX_DESC_BYTES)
            point_data["desc"] = body[offset : offset + read_len].decode("utf-8", errors="replace")
        offset += desc_len

        points.append(point_data)

    return tracker_uuid, points, None


def _get_request_body_decompressed(request):
    """Return request body, decompressing if Content-Encoding is gzip or deflate."""
    body = request.body
    encoding = (request.META.get("HTTP_CONTENT_ENCODING") or "").strip().lower()
    if encoding == "gzip":
        try:
            return gzip.decompress(body)
        except (OSError, ValueError) as e:
            logger.warning("app_ingress: gzip decompress failed: %s", e)
            return None
    if encoding == "deflate":
        try:
            import zlib
            return zlib.decompress(body)
        except zlib.error as e:
            logger.warning("app_ingress: deflate decompress failed: %s", e)
            return None
    return body


@api_or_login_required_401()
@handle_404
@require_http_methods(["POST"])
@csrf_exempt
def app_ingress(request):
    body = _get_request_body_decompressed(request)
    if body is None:
        return error_response("Invalid or unsupported Content-Encoding", 400)
    if body.startswith(b"GVLM"):
        tracker_uuid, points, err = _parse_gvlm_minimal(body)
    elif body.startswith(b"GVLT"):
        tracker_uuid, points, err = _parse_gvlt_points(body)
    else:
        return error_response("Invalid magic bytes", 400)
    if err is not None:
        return error_response(err, 400)
    if tracker_uuid is None or points is None:
        return error_response("Invalid payload", 400)

    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_uuid)

    cache_backend = getattr(settings, "CACHES", {}).get("default", {}).get("BACKEND", "")
    if "redis" in cache_backend.lower():
        rate_key = f"live_track_ingress:{track.id}"
        now_ts = timezone.now().timestamp()
        last = cache.get(rate_key)
        if last is not None and (now_ts - last) < 1.0:
            return error_response("Rate limit exceeded", 429)
        cache.set(rate_key, now_ts, timeout=2)

    if not points:
        return JsonResponse({"ok": True}, status=200)

    max_points = get_config_loader().get_int("extensions.live_track.max_points", 1000)

    with transaction.atomic():
        track_locked = LiveTrack.objects.select_for_update().get(pk=track.id)
        geom = track_locked.geometry or {"type": "LineString", "coordinates": []}
        coords = list(geom.get("coordinates") or [])
        point_params = list(track_locked.point_params or [])

        ts_list = [c[2] for c in coords]
        for point_data in points:
            new_point = [point_data["lon"], point_data["lat"], point_data["timestamp"]]
            extra = {k: v for k, v in point_data.items() if k not in ("lat", "lon", "timestamp")}

            idx = bisect.bisect_right(ts_list, point_data["timestamp"])
            coords.insert(idx, new_point)
            point_params.insert(idx, extra)
            ts_list.insert(idx, point_data["timestamp"])

        if len(coords) > max_points:
            n_removed = len(coords) - max_points
            coords = coords[n_removed:]
            point_params = point_params[n_removed:]

        track_locked.geometry = {"type": "LineString", "coordinates": coords}
        track_locked.point_params = point_params
        track_locked.updated_at = timezone.now()
        track_locked.save(update_fields=["geometry", "point_params", "updated_at"])

        last_point_data = points[-1]
        last_new_point = [last_point_data["lon"], last_point_data["lat"], last_point_data["timestamp"]]
        last_extra = {k: v for k, v in last_point_data.items() if k not in ("lat", "lon", "timestamp")}
        try:
            broadcast_idx = coords.index(last_new_point)
        except ValueError:
            broadcast_idx = None

    if broadcast_idx is not None:
        if not queue_broadcast_track_updated(track, last_new_point, last_extra, index=broadcast_idx):
            broadcast_track_updated(track, last_new_point, last_extra, index=broadcast_idx)

    return JsonResponse({"ok": True}, status=200)
