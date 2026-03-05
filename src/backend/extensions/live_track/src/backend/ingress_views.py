"""
Ingress endpoints: POST-only, Basic Auth, rate limit, insert point by timestamp.
"""

import base64
import bisect

from django.conf import settings
from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.db import transaction
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods
from geo_lib.logging.console import get_tagged_logger
from pydantic import ValidationError as PydanticValidationError

from api.utils.responses import error_response
from website.config_loader import get_config_loader

from .helpers import broadcast_track_updated, parse_ingress_body, parse_time_to_ms
from .models import LiveTrack
from .validation import LiveTrackIngressBody

User = get_user_model()
logger = get_tagged_logger()


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

    max_points = get_config_loader().get_int("extensions.live_track.max_points", 1000)

    with transaction.atomic():
        track_locked = LiveTrack.objects.select_for_update().get(pk=track.id)
        geom = track_locked.geometry or {"type": "LineString", "coordinates": []}
        coords = list(geom.get("coordinates") or [])
        point_params = list(track_locked.point_params or [])

        new_point = [body.lon, body.lat, timestamp_ms]
        extra = {k: v for k, v in body.model_dump().items() if k not in ("lat", "lon", "timestamp") and v is not None}

        ts_list = [c[2] for c in coords]
        idx = bisect.bisect_right(ts_list, timestamp_ms)
        coords.insert(idx, new_point)
        point_params.insert(idx, extra)

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
        broadcast_track_updated(user.id, str(track.id), new_point, extra, index=broadcast_idx)
    return JsonResponse({"ok": True}, status=200)


@require_http_methods(["POST"])
@csrf_exempt
def app_ingress(request):
    return error_response("Not implemented", 501)
